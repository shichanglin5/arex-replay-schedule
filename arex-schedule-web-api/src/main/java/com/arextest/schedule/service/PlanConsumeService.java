package com.arextest.schedule.service;

import com.arextest.schedule.bizlog.BizLogger;
import com.arextest.schedule.common.CommonConstant;
import com.arextest.schedule.common.SendSemaphoreLimiter;
import com.arextest.schedule.dao.mongodb.ReplayActionCaseItemRepository;
import com.arextest.schedule.dao.mongodb.ReplayBizLogRepository;
import com.arextest.schedule.dao.mongodb.ReplayPlanRepository;
import com.arextest.schedule.mdc.AbstractTracedRunnable;
import com.arextest.schedule.mdc.MDCTracer;
import com.arextest.schedule.model.*;
import com.arextest.schedule.model.bizlog.BizLog;
import com.arextest.schedule.planexecution.PlanExecutionContextProvider;
import com.arextest.schedule.progress.ProgressEvent;
import com.arextest.schedule.progress.ProgressTracer;
import com.arextest.schedule.model.ReplayActionCaseItem;
import com.arextest.schedule.utils.ReplayParentBinder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * @author jmo
 * @since 2021/9/15
 */
@Slf4j
@Service
@SuppressWarnings("rawtypes")
public final class PlanConsumeService {
    @Resource
    private ReplayCaseRemoteLoadService caseRemoteLoadService;
    @Resource
    private ReplayActionCaseItemRepository replayActionCaseItemRepository;
    @Resource
    private ReplayBizLogRepository replayBizLogRepository;
    @Resource
    private ReplayCaseTransmitService replayCaseTransmitService;
    @Resource
    private ExecutorService preloadExecutorService;
    @Resource
    private ExecutorService actionItemParallelPool;
    @Resource
    private ReplayPlanRepository replayPlanRepository;
    @Resource
    private ProgressTracer progressTracer;
    @Resource
    private ProgressEvent progressEvent;
    @Resource
    private MetricService metricService;
    @Resource
    private PlanExecutionContextProvider planExecutionContextProvider;
    @Resource
    private ReplayReportService replayReportService;

    @Value("${arex.schedule.bizLog.sizeToSave}")
    private int LOG_SIZE_TO_SAVE_CHECK;
    @Value("${arex.schedule.bizLog.secondToSave}")
    private long LOG_TIME_GAP_TO_SAVE_CHECK_BY_SEC;


    public void runAsyncConsume(ReplayPlan replayPlan) {
        BizLogger.recordPlanAsyncStart(replayPlan);
        // TODO: remove block thread use async to load & send for all
        preloadExecutorService.execute(new ReplayActionLoadingRunnableImpl(replayPlan));
    }

    private final class ReplayActionLoadingRunnableImpl extends AbstractTracedRunnable {
        private final ReplayPlan replayPlan;

        private ReplayActionLoadingRunnableImpl(ReplayPlan replayPlan) {
            this.replayPlan = replayPlan;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void doWithTracedRunning() {
            try {
                long start;
                long end;

                start = System.currentTimeMillis();
                int planSavedCaseSize = saveActionCaseToSend(replayPlan);
                end = System.currentTimeMillis();
                BizLogger.recordPlanCaseSaved(replayPlan, planSavedCaseSize, end - start);

                start = System.currentTimeMillis();
                replayPlan.setExecutionContexts(planExecutionContextProvider.buildContext(replayPlan));
                end = System.currentTimeMillis();

                BizLogger.recordContextBuilt(replayPlan, end - start);

                if (CollectionUtils.isEmpty(replayPlan.getExecutionContexts())) {
                    LOGGER.error("Invalid context built for plan {}", replayPlan);
                    replayPlan.setErrorMessage("Got empty execution context");
                    progressEvent.onReplayPlanInterrupt(replayPlan, ReplayStatusType.FAIL_INTERRUPTED);
                    BizLogger.recordPlanStatusChange(replayPlan, ReplayStatusType.FAIL_INTERRUPTED.name(),
                            "NO context to execute");
                    return;
                }

                sendAllActionCase(replayPlan);

                // actionItems with empty case item are not able to trigger plan finished hook
                if (planSavedCaseSize == 0) {
                    progressEvent.onReplayPlanFinish(replayPlan);
                }
            } catch (Throwable t) {
                BizLogger.recordPlanException(replayPlan, t);
                throw t;
            } finally {
                replayBizLogRepository.saveAll(replayPlan.getBizLogs());
            }
        }
    }

    private int saveActionCaseToSend(ReplayPlan replayPlan) {
        metricService.recordTimeEvent(LogType.PLAN_EXECUTION_DELAY.getValue(), replayPlan.getId(), replayPlan.getAppId(), null,
                System.currentTimeMillis() - replayPlan.getPlanCreateMillis());
        int planSavedCaseSize = saveAllActionCase(replayPlan.getReplayActionItemList());
        if (planSavedCaseSize != replayPlan.getCaseTotalCount()) {
            LOGGER.info("update the plan TotalCount, plan id:{} ,appId: {} , size: {} -> {}", replayPlan.getId(),
                    replayPlan.getAppId(), replayPlan.getCaseTotalCount(), planSavedCaseSize);
            replayPlan.setCaseTotalCount(planSavedCaseSize);
            replayPlanRepository.updateCaseTotal(replayPlan.getId(), planSavedCaseSize);
            replayReportService.updateReportCaseCount(replayPlan);
        }
        return planSavedCaseSize;
    }

    private int saveAllActionCase(List<ReplayActionItem> replayActionItemList) {
        int planSavedCaseSize = 0;
        long start;
        long end;

        for (ReplayActionItem replayActionItem : replayActionItemList) {
            if (replayActionItem.getReplayStatus() != ReplayStatusType.INIT.getValue()) {
                planSavedCaseSize += replayActionItem.getReplayCaseCount();
                continue;
            }
            int preloaded = replayActionItem.getReplayCaseCount();

            start = System.currentTimeMillis();
            int actionSavedCount = streamingCaseItemSave(replayActionItem);
            end = System.currentTimeMillis();
            BizLogger.recordActionItemCaseSaved(replayActionItem, actionSavedCount, end - start);

            planSavedCaseSize += actionSavedCount;
            if (preloaded != actionSavedCount) {
                replayActionItem.setReplayCaseCount(actionSavedCount);
                LOGGER.warn("The saved case size of actionItem not equals, preloaded size:{},saved size:{}", preloaded,
                        actionSavedCount);
            }
            progressEvent.onActionCaseLoaded(replayActionItem);
        }
        return planSavedCaseSize;
    }

    @SuppressWarnings("unchecked")
    private void sendAllActionCase(ReplayPlan replayPlan) {
        long start;
        long end;
        progressTracer.initTotal(replayPlan);

        // limiter shared for entire plan, max qps = maxQps per instance * min instance count
        final SendSemaphoreLimiter qpsLimiter = new SendSemaphoreLimiter(replayPlan.getReplaySendMaxQps(),
                replayPlan.getMinInstanceCount());
        qpsLimiter.setTotalTasks(replayPlan.getCaseTotalCount());
        qpsLimiter.setReplayPlan(replayPlan);

        BizLogger.recordQpsInit(replayPlan, qpsLimiter.getPermits(), replayPlan.getMinInstanceCount());

        ExecutionStatus sendResult = ExecutionStatus.buildNormal();
        for (PlanExecutionContext executionContext : replayPlan.getExecutionContexts()) {
            executionContext.setExecutionStatus(sendResult);
            executionContext.setPlan(replayPlan);

            start = System.currentTimeMillis();
            planExecutionContextProvider.onBeforeContextExecution(executionContext, replayPlan);
            end = System.currentTimeMillis();
            BizLogger.recordContextBeforeRun(executionContext, end - start);

            executeContext(replayPlan, qpsLimiter, executionContext);

            start = System.currentTimeMillis();
            planExecutionContextProvider.onAfterContextExecution(executionContext, replayPlan);
            end = System.currentTimeMillis();
            BizLogger.recordContextAfterRun(executionContext, end - start);

            // todo move flushing job to external monitor thread
            tryFlushingLogs(replayPlan);
        }

        if (sendResult.isCanceled()) {
            progressEvent.onReplayPlanFinish(replayPlan, ReplayStatusType.CANCELLED);
            BizLogger.recordPlanStatusChange(replayPlan, ReplayStatusType.CANCELLED.name(), "Plan Canceled");
            return;
        }

        if (sendResult.isInterrupted()) {
            progressEvent.onReplayPlanInterrupt(replayPlan, ReplayStatusType.FAIL_INTERRUPTED);

            // todo, fix the qps limiter counter
            BizLogger.recordPlanStatusChange(replayPlan, ReplayStatusType.FAIL_INTERRUPTED.name(),
                    "Plan Interrupted by QPS limiter.");
            return;
        }
        BizLogger.recordPlanDone(replayPlan);
        LOGGER.info("All the plan action sent,waiting to compare, plan id:{} ,appId: {} ", replayPlan.getId(),
                replayPlan.getAppId());
    }

    private void executeContext(ReplayPlan replayPlan, SendSemaphoreLimiter qpsLimiter, PlanExecutionContext executionContext) {
        List<CompletableFuture<Void>> contextTasks = new ArrayList<>();
        for (ReplayActionItem replayActionItem : replayPlan.getReplayActionItemList()) {
            if (!replayActionItem.isItemProcessed()) {
                replayActionItem.setItemProcessed(true);
                MDCTracer.addActionId(replayActionItem.getId());
                replayActionItem.setSendRateLimiter(qpsLimiter);
                if (replayActionItem.isEmpty()) {
                    replayActionItem.setReplayFinishTime(new Date());
                    progressEvent.onActionComparisonFinish(replayActionItem);
                    BizLogger.recordActionStatusChange(replayActionItem, ReplayStatusType.FINISHED.name(),
                            "No case needs to be sent");
                }
            }

            if (replayActionItem.finished() || replayActionItem.isEmpty()) {
                LOGGER.warn("Skipped action item: {}, finished: {}, empty: {}",
                        replayActionItem.getAppId(), replayActionItem.finished(), replayActionItem.isEmpty());
                continue;
            }
            CompletableFuture<Void> task = CompletableFuture.runAsync(
                    () -> sendItemByContext(replayActionItem, executionContext),
                    actionItemParallelPool);
            contextTasks.add(task);
        }

        CompletableFuture.allOf(contextTasks.toArray(new CompletableFuture[0])).join();
    }

    private void sendItemByContext(ReplayActionItem replayActionItem, PlanExecutionContext currentContext) {
        BizLogger.recordActionUnderContext(replayActionItem, currentContext);

        ExecutionStatus sendResult = currentContext.getExecutionStatus();
        if (sendResult.isCanceled() && replayActionItem.getReplayStatus() != ReplayStatusType.CANCELLED.getValue()) {
            BizLogger.recordActionStatusChange(replayActionItem, ReplayStatusType.CANCELLED.name(), "");
            progressEvent.onActionCancelled(replayActionItem);
            return;
        }

        if (sendResult.isInterrupted() && replayActionItem.getReplayStatus() != ReplayStatusType.FAIL_INTERRUPTED.getValue()) {
            progressEvent.onActionInterrupted(replayActionItem);
            return;
        }

        if (replayActionItem.getReplayFinishTime() == null
                && replayActionItem.getReplayStatus() != ReplayStatusType.RUNNING.getValue()) {
            progressEvent.onActionBeforeSend(replayActionItem);
        }

        this.sendByPaging(replayActionItem, currentContext);

        if (sendResult.isInterrupted() && replayActionItem.getReplayStatus() != ReplayStatusType.FAIL_INTERRUPTED.getValue()) {
            progressEvent.onActionInterrupted(replayActionItem);
        }

        if (sendResult.isNormal() && (replayActionItem.getReplayCaseCount() == replayActionItem.getCaseProcessCount())) {
            progressEvent.onActionAfterSend(replayActionItem);
            BizLogger.recordActionItemSent(replayActionItem);
        }
    }

    private void sendByPaging(ReplayActionItem replayActionItem, PlanExecutionContext executionContext) {
        ExecutionStatus sendResult = executionContext.getExecutionStatus();
        switch (executionContext.getActionType()) {
            case SKIP_CASE_OF_CONTEXT:
                // skip all cases of this context leaving the status as default
                sendResult.setCanceled(replayCaseTransmitService.releaseCasesOfContext(replayActionItem, executionContext));
                sendResult.setInterrupted(replayActionItem.getSendRateLimiter().failBreak());
                break;

            case NORMAL:
            default:
                int contextCount = 0;
                List<ReplayActionCaseItem> sourceItemList;
                while (true) {
                    sourceItemList = replayActionCaseItemRepository.waitingSendList(replayActionItem.getId(),
                            CommonConstant.MAX_PAGE_SIZE, executionContext.getContextCaseQuery());

                    replayActionItem.setCaseItemList(sourceItemList);
                    if (CollectionUtils.isEmpty(sourceItemList)) {
                        break;
                    }
                    contextCount += sourceItemList.size();

                            ReplayParentBinder.setupCaseItemParent(sourceItemList, replayActionItem);
                    sendResult.setInterrupted(replayActionItem.getSendRateLimiter().failBreak());

                    if (sendResult.isInterrupted() || sendResult.isCanceled()) {
                        break;
                    }
                    sendResult.setCanceled(replayCaseTransmitService.send(replayActionItem));

                    BizLogger.recordActionItemBatchSent(replayActionItem, sourceItemList.size());
                }
                BizLogger.recordContextProcessedNormal(executionContext, replayActionItem, contextCount);
        }
    }

    private int streamingCaseItemSave(ReplayActionItem replayActionItem) {
        List<ReplayActionCaseItem> caseItemList = replayActionItem.getCaseItemList();
        int size;
        if (CollectionUtils.isNotEmpty(caseItemList)) {
            size = doFixedCaseSave(caseItemList);
        } else {
            size = doPagingLoadCaseSave(replayActionItem);
        }
        return size;
    }

    private int doFixedCaseSave(List<ReplayActionCaseItem> caseItemList) {
        int size = 0;
        for (int i = 0; i < caseItemList.size(); i++) {
            ReplayActionCaseItem caseItem = caseItemList.get(i);
            Set<String> operationTypes = caseItem.getParent().getOperationTypes();
            ReplayActionCaseItem viewReplay = caseRemoteLoadService.viewReplayLoad(caseItem, operationTypes);
            if (viewReplay == null) {
                caseItem.setSendStatus(CaseSendStatusType.REPLAY_CASE_NOT_FOUND.getValue());
                caseItem.setSourceResultId(StringUtils.EMPTY);
                caseItem.setTargetResultId(StringUtils.EMPTY);
            } else {
                viewReplay.setParent(caseItem.getParent());
                caseItemList.set(i, viewReplay);
                size++;
            }
        }

        caseItemPostProcess(caseItemList);
        replayActionCaseItemRepository.save(caseItemList);
        return size;
    }

    private void caseItemPostProcess(List<ReplayActionCaseItem> caseItemList) {
        // to provide necessary fields into case item for context to consume when sending
        planExecutionContextProvider.injectContextIntoCase(caseItemList);
    }

    /**
     * Paging query storage's recording data.
     * if caseCountLimit > CommonConstant.MAX_PAGE_SIZE, Calculate the latest pageSize and recycle pagination queries
     * <p>
     * else if caseCountLimit < CommonConstant.MAX_PAGE_SIZE or recording data size < request page size,
     * Only need to query once by page
     */
    private int doPagingLoadCaseSave(ReplayActionItem replayActionItem) {
        final ReplayPlan replayPlan = replayActionItem.getParent();
        long beginTimeMills = replayActionItem.getLastRecordTime();
        if (beginTimeMills == 0) {
            beginTimeMills = replayPlan.getCaseSourceFrom().getTime();
        }
        long endTimeMills = replayPlan.getCaseSourceTo().getTime();
        int totalSize = 0;
        int caseCountLimit = replayActionItem.getOperationTypes() == null ? replayPlan.getCaseCountLimit() : replayPlan.getCaseCountLimit() * replayActionItem.getOperationTypes().size();
        int pageSize = Math.min(caseCountLimit, CommonConstant.MAX_PAGE_SIZE);
        while (beginTimeMills < endTimeMills) {
            List<ReplayActionCaseItem> caseItemList = caseRemoteLoadService.pagingLoad(beginTimeMills, endTimeMills,
                    replayActionItem, caseCountLimit - totalSize);
            if (CollectionUtils.isEmpty(caseItemList)) {
                break;
            }
            caseItemPostProcess(caseItemList);
            ReplayParentBinder.setupCaseItemParent(caseItemList, replayActionItem);
            totalSize += caseItemList.size();
            beginTimeMills = caseItemList.get(caseItemList.size() - 1).getRecordTime();
            replayActionCaseItemRepository.save(caseItemList);
            if (totalSize >= caseCountLimit || caseItemList.size() < pageSize) {
                break;
            }
            replayActionItem.setLastRecordTime(beginTimeMills);
        }
        return totalSize;
    }

    private void tryFlushingLogs(ReplayPlan replayPlan) {
        try {
            BlockingQueue<BizLog> logs = replayPlan.getBizLogs();
            if (logs.size() > LOG_SIZE_TO_SAVE_CHECK
                    || (System.currentTimeMillis() - replayPlan.getLastLogTime()) > LOG_TIME_GAP_TO_SAVE_CHECK_BY_SEC * 1000L) {
                List<BizLog> logsToSave = new ArrayList<>();
                int curSize = replayPlan.getBizLogs().size();
                for (int i = 0; i < curSize; i++) {
                    logsToSave.add(logs.remove());
                }
                replayPlan.setLastLogTime(System.currentTimeMillis());
                replayBizLogRepository.saveAll(logsToSave);
            }
        } catch (Throwable t) {
            LOGGER.error("Error flushing biz logs", t);
        }
    }
}