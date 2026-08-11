package com.kwang.study.mathvision.engine;

import com.kwang.study.mathvision.enums.StageEnum;
import com.kwang.study.mathvision.mapper.MathVisionArtifactMapper;
import com.kwang.study.mathvision.mapper.MathVisionStageResultMapper;
import com.kwang.study.mathvision.mapper.MathVisionTaskMapper;
import com.kwang.study.mathvision.mapper.MathVisionVersionMapper;
import com.kwang.study.mathvision.pojo.MathVisionArtifact;
import com.kwang.study.mathvision.pojo.MathVisionStageResult;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.pojo.MathVisionVersion;
import com.kwang.study.mathvision.service.MathVisionTaskNotifier;
import com.kwang.study.mathvision.service.MathVisionWorkflowSummaryService;
import com.kwang.study.mathvision.workflow.model.StageGenerationMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

@Service
public class MathVisionStageRunner {

    private static final Logger log = LoggerFactory.getLogger(MathVisionStageRunner.class);
    private static final List<StageEnum> VISIBLE_STAGES = Arrays.asList(
            StageEnum.PROBLEM_NORMALIZATION,
            StageEnum.REASONING_GRAPH,
            StageEnum.VISUAL_STORYBOARD,
            StageEnum.CODE_GENERATION,
            StageEnum.RENDER_RESULT
    );

    private final MathVisionTaskMapper taskMapper;
    private final MathVisionArtifactMapper artifactMapper;
    private final MathVisionStageResultMapper stageResultMapper;
    private final MathVisionVersionMapper versionMapper;
    private final MathVisionStageExecutorRegistry executorRegistry;
    private final MathVisionTaskNotifier taskNotifier;
    private final MathVisionWorkflowSummaryService workflowSummaryService;

    public MathVisionStageRunner(MathVisionTaskMapper taskMapper,
                                 MathVisionArtifactMapper artifactMapper,
                                 MathVisionStageResultMapper stageResultMapper,
                                 MathVisionVersionMapper versionMapper,
                                 MathVisionStageExecutorRegistry executorRegistry,
                                 MathVisionTaskNotifier taskNotifier,
                                 MathVisionWorkflowSummaryService workflowSummaryService) {
        this.taskMapper = taskMapper;
        this.artifactMapper = artifactMapper;
        this.stageResultMapper = stageResultMapper;
        this.versionMapper = versionMapper;
        this.executorRegistry = executorRegistry;
        this.taskNotifier = taskNotifier;
        this.workflowSummaryService = workflowSummaryService;
    }

    public void runOneVisibleStage(Long taskId) {
        MathVisionTask task = taskMapper.findById(taskId);
        if (task == null) {
            return;
        }
        StageEnum stage = StageEnum.fromCode(task.getCurrentStage());
        if (!VISIBLE_STAGES.contains(stage)) {
            log.warn("MathVision 阶段不可运行, taskId={}, stage={}", taskId, task.getCurrentStage());
            taskMapper.markFailed(taskId, task.getCurrentStage(), "workflow_error", "当前阶段不可运行");
            taskNotifier.notifyTaskChanged(taskId, "failed");
            return;
        }
        try {
            checkCanceled(taskId);
            MathVisionStageExecutor executor = executorRegistry.find(stage)
                    .orElseThrow(() -> new IllegalStateException("阶段执行器尚未接入: " + stage.getCode()));
            String existingStageResultJson = currentStageResultJson(task, stage);
            boolean qualityReviewRequested = MathVisionStageQualityReview.isRequested(
                    stage, existingStageResultJson);
            // A review resumes the already persisted stage artifact. It must not be treated as
            // another user-revision generation just because the task version originated there.
            UserRevisionContext revisionContext = qualityReviewRequested
                    ? null
                    : resolveUserRevisionContext(task, stage);
            log.info("MathVision 阶段开始执行, taskId={}, stage={}, mode={}, currentVersion={}",
                    taskId, stage.getCode(), task.getMode(), task.getCurrentVersion());
            MathVisionStageExecutionContext context = MathVisionStageExecutionContext.builder()
                    .task(task)
                    .stage(stage)
                    .cancellationCheck(() -> checkCanceled(taskId))
                    .generationMode(revisionContext != null
                            ? StageGenerationMode.USER_REVISION
                            : StageGenerationMode.INITIAL_GENERATION)
                    .baseStageVersion(revisionContext != null ? revisionContext.baseStageVersion : null)
                    .instruction(revisionContext != null ? revisionContext.instruction : null)
                    .existingArtifactJson(revisionContext != null ? revisionContext.existingArtifactJson : null)
                    .existingStageResultJson(existingStageResultJson)
                    .qualityReviewRequested(qualityReviewRequested)
                    .stopAfterStage(revisionContext != null)
                    .build();
            MathVisionStageExecutionResult result = executor.execute(context);
            checkCanceled(taskId);
            boolean failed = result != null && result.isFailed();
            if (!failed || !context.isUserRevision()) {
                persistStageResult(task, stage, result, context);
                log.debug("MathVision 阶段结果已持久化, taskId={}, stage={}, hasArtifact={}, hasResult={}, finalArtifactPath={}",
                        taskId,
                        stage.getCode(),
                        result != null && StringUtils.hasText(result.getArtifactJson()),
                        result != null && StringUtils.hasText(result.getResultJson()),
                        result == null ? null : result.getFinalArtifactPath());
            }
            if (failed) {
                String errorType = StringUtils.hasText(result.getErrorType())
                        ? result.getErrorType()
                        : resolveErrorType(stage);
                String errorMessage = StringUtils.hasText(result.getErrorMessage())
                        ? result.getErrorMessage()
                        : "阶段执行失败";
                log.warn("MathVision 阶段产出失败结果, taskId={}, stage={}, errorType={}, errorMessage={}",
                        taskId, stage.getCode(), errorType, trimMessage(errorMessage));
                taskMapper.markFailed(taskId, stage.getCode(), errorType, trimMessage(errorMessage));
                refreshWorkflowSummary(taskId);
                taskNotifier.notifyTaskChanged(taskId, "failed");
                return;
            }
            advanceAfterStage(task, stage, context, result);
            refreshWorkflowSummary(taskId);
        } catch (MathVisionTaskCanceledException e) {
            log.info("MathVision 任务已取消, taskId={}, stage={}", taskId, stage.getCode());
            taskMapper.markCanceled(taskId);
            refreshWorkflowSummary(taskId);
            taskNotifier.notifyTaskChanged(taskId, "canceled");
        } catch (Exception e) {
            log.warn("MathVision 阶段执行失败, taskId={}, stage={}: {}", taskId, stage.getCode(), e.getMessage(), e);
            taskMapper.markFailed(taskId, stage.getCode(), resolveErrorType(stage), trimMessage(e.getMessage()));
            refreshWorkflowSummary(taskId);
            taskNotifier.notifyTaskChanged(taskId, "failed");
        }
    }

    private void refreshWorkflowSummary(Long taskId) {
        try {
            MathVisionTask latest = taskMapper.findById(taskId);
            workflowSummaryService.refresh(latest);
        } catch (Exception e) {
            log.warn("MathVision workflow summary refresh failed, taskId={}: {}", taskId, e.getMessage(), e);
        }
    }

    private void persistStageResult(MathVisionTask task,
                                    StageEnum stage,
                                    MathVisionStageExecutionResult result,
                                    MathVisionStageExecutionContext context) {
        if (result == null) {
            result = MathVisionStageExecutionResult.builder().build();
        }
        Long artifactId = null;
        Integer insertedStageVersion = null;
        if (StringUtils.hasText(result.getArtifactJson())) {
            Integer currentStageVersion = currentStageVersion(task, stage);
            MathVisionArtifact artifact = currentStageVersion != null
                    ? artifactMapper.findByTaskStageVersion(task.getId(), stage.getCode(), currentStageVersion)
                    : null;
            String changeSource = context.isUserRevision()
                    ? "user_revision"
                    : result.resolvedChangeSource();
            String changeSummary = context.isUserRevision()
                    ? revisionSummary(context.getInstruction())
                    : result.getChangeSummary();
            if (artifact != null && !context.isUserRevision()) {
                artifact.setArtifactJson(result.getArtifactJson());
                artifact.setChangeSource(changeSource);
                artifact.setChangeSummary(changeSummary);
                artifactMapper.updateArtifactJson(artifact);
                artifactId = artifact.getId();
                insertedStageVersion = currentStageVersion;
            } else {
                Integer maxVersion = artifactMapper.findMaxVersion(task.getId(), stage.getCode());
                int stageVersion = maxVersion == null ? 1 : maxVersion + 1;
                insertedStageVersion = stageVersion;
                artifact = MathVisionArtifact.builder()
                        .taskId(task.getId())
                        .sessionId(task.getSessionId())
                        .userId(task.getUserId())
                        .stage(stage.getCode())
                        .version(stageVersion)
                        .baseVersion(context.isUserRevision()
                                ? context.getBaseStageVersion()
                                : currentStageVersion)
                        .artifactJson(result.getArtifactJson())
                        .changeSource(changeSource)
                        .changeSummary(changeSummary)
                        .build();
                artifactMapper.insert(artifact);
                artifactId = artifact.getId();
                versionMapper.updateStagePointer(
                        task.getId(), task.getCurrentVersion(), stage.getCode(), stageVersion);
            }
        }
        if (artifactId != null && StringUtils.hasText(result.getResultJson())) {
            MathVisionStageResult stageResult = stageResultMapper.findByArtifactId(artifactId);
            if (stageResult != null) {
                stageResult.setResultJson(result.getResultJson());
                stageResultMapper.updateResultJson(stageResult);
            } else {
                stageResult = MathVisionStageResult.builder()
                        .taskId(task.getId())
                        .artifactId(artifactId)
                        .sessionId(task.getSessionId())
                        .userId(task.getUserId())
                        .stage(stage.getCode())
                        .version(insertedStageVersion)
                        .resultJson(result.getResultJson())
                        .build();
                stageResultMapper.insert(stageResult);
            }
        }
        if (stage == StageEnum.RENDER_RESULT && StringUtils.hasText(result.getFinalArtifactPath())) {
            MathVisionTask update = MathVisionTask.builder()
                    .id(task.getId())
                    .finalArtifactPath(result.getFinalArtifactPath())
                    .finalArtifactType(result.getFinalArtifactType())
                    .build();
            taskMapper.updateFinalArtifact(update);
        }
    }

    private void advanceAfterStage(MathVisionTask task,
                                   StageEnum stage,
                                   MathVisionStageExecutionContext context,
                                   MathVisionStageExecutionResult result) {
        if (result != null && result.isWaitForUserDecision()) {
            log.info("MathVision 阶段等待用户选择是否执行智能检查, taskId={}, stage={}",
                    task.getId(), stage.getCode());
            taskMapper.markWaitingConfirm(task.getId(), stage.getCode());
            taskNotifier.notifyTaskChanged(task.getId(), "waiting_confirm");
            return;
        }
        if (context.isUserRevision() && context.isStopAfterStage()) {
            log.info("MathVision 自动编辑完成并等待确认, taskId={}, stage={}, baseStageVersion={}",
                    task.getId(), stage.getCode(), context.getBaseStageVersion());
            taskMapper.markWaitingConfirm(task.getId(), stage.getCode());
            taskNotifier.notifyTaskChanged(task.getId(), "waiting_confirm");
            return;
        }
        StageEnum next = nextStage(stage);
        MathVisionTask latestTask = taskMapper.findById(task.getId());
        String effectiveMode = latestTask != null && StringUtils.hasText(latestTask.getMode())
                ? latestTask.getMode()
                : task.getMode();
        if ("manual".equals(effectiveMode)) {
            if (next == StageEnum.COMPLETED) {
                log.info("MathVision 任务已完成, taskId={}, completedStage={}, mode={}",
                        task.getId(), stage.getCode(), effectiveMode);
                taskMapper.markCompleted(task.getId());
                taskNotifier.notifyTaskChanged(task.getId(), "completed");
            } else {
                log.info("MathVision 任务等待人工确认, taskId={}, completedStage={}, nextStage={}, mode={}",
                        task.getId(), stage.getCode(), next.getCode(), effectiveMode);
                taskMapper.markWaitingConfirm(task.getId(), stage.getCode());
                taskNotifier.notifyTaskChanged(task.getId(), "waiting_confirm");
            }
            return;
        }
        if (next == StageEnum.COMPLETED) {
            log.info("MathVision 任务已完成, taskId={}, completedStage={}, mode={}",
                    task.getId(), stage.getCode(), effectiveMode);
            taskMapper.markCompleted(task.getId());
            taskNotifier.notifyTaskChanged(task.getId(), "completed");
        } else {
            log.info("MathVision 下一阶段已入队, taskId={}, completedStage={}, nextStage={}, mode={}",
                    task.getId(), stage.getCode(), next.getCode(), effectiveMode);
            taskMapper.queueNextStage(task.getId(), next.getCode());
            taskNotifier.notifyTaskChanged(task.getId(), "queued");
        }
    }

    private StageEnum nextStage(StageEnum stage) {
        int idx = VISIBLE_STAGES.indexOf(stage);
        if (idx < 0 || idx == VISIBLE_STAGES.size() - 1) {
            return StageEnum.COMPLETED;
        }
        return VISIBLE_STAGES.get(idx + 1);
    }

    private void checkCanceled(Long taskId) {
        MathVisionTask latest = taskMapper.findById(taskId);
        if (latest == null || Boolean.TRUE.equals(latest.getCancelRequested())) {
            throw new MathVisionTaskCanceledException();
        }
    }

    private UserRevisionContext resolveUserRevisionContext(MathVisionTask task, StageEnum stage) {
        MathVisionVersion version = task.getCurrentVersion() != null
                ? versionMapper.findByTaskVersion(task.getId(), task.getCurrentVersion())
                : versionMapper.findCurrent(task.getId());
        if (version == null
                || !"user_revision".equals(version.getChangeSource())
                || !stage.getCode().equals(version.getBranchStage())) {
            return null;
        }
        Integer baseStageVersion = stageVersionOf(version, stage);
        String instruction = version.getChangeInstruction() != null
                ? version.getChangeInstruction().trim()
                : "";
        if (baseStageVersion == null
                || baseStageVersion <= 0
                || !StringUtils.hasText(instruction)) {
            throw new IllegalStateException("自动编辑基线版本或修改意见无效");
        }
        MathVisionArtifact baseline = artifactMapper.findByTaskStageVersion(
                task.getId(), stage.getCode(), baseStageVersion);
        if (baseline == null || !StringUtils.hasText(baseline.getArtifactJson())) {
            throw new IllegalStateException("自动编辑基线阶段产物不存在");
        }
        return new UserRevisionContext(
                baseStageVersion,
                instruction,
                baseline.getArtifactJson());
    }

    private Integer currentStageVersion(MathVisionTask task, StageEnum stage) {
        MathVisionVersion version = task.getCurrentVersion() != null
                ? versionMapper.findByTaskVersion(task.getId(), task.getCurrentVersion())
                : versionMapper.findCurrent(task.getId());
        if (version == null) {
            return null;
        }
        return stageVersionOf(version, stage);
    }

    private String currentStageResultJson(MathVisionTask task, StageEnum stage) {
        Integer stageVersion = currentStageVersion(task, stage);
        if (stageVersion == null) {
            return null;
        }
        MathVisionStageResult result = stageResultMapper.findByTaskStageVersion(
                task.getId(), stage.getCode(), stageVersion);
        return result != null ? result.getResultJson() : null;
    }

    private Integer stageVersionOf(MathVisionVersion version, StageEnum stage) {
        if (StageEnum.PROBLEM_NORMALIZATION.equals(stage)) {
            return version.getPnVersion();
        }
        if (StageEnum.REASONING_GRAPH.equals(stage)) {
            return version.getRgVersion();
        }
        if (StageEnum.VISUAL_STORYBOARD.equals(stage)) {
            return version.getVsVersion();
        }
        if (StageEnum.CODE_GENERATION.equals(stage)) {
            return version.getCgVersion();
        }
        if (StageEnum.RENDER_RESULT.equals(stage)) {
            return version.getRrVersion();
        }
        return null;
    }

    private String revisionSummary(String instruction) {
        String summary = "user revision";
        if (StringUtils.hasText(instruction)) {
            summary += ": " + instruction.trim();
        }
        return summary.length() > 500 ? summary.substring(0, 500) : summary;
    }

    private String resolveErrorType(StageEnum stage) {
        if (stage == StageEnum.CODE_GENERATION) {
            return "code_error";
        }
        if (stage == StageEnum.RENDER_RESULT) {
            return "render_error";
        }
        return "workflow_error";
    }

    private String trimMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return "阶段执行失败";
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private static final class UserRevisionContext {
        private final Integer baseStageVersion;
        private final String instruction;
        private final String existingArtifactJson;

        private UserRevisionContext(Integer baseStageVersion,
                                    String instruction,
                                    String existingArtifactJson) {
            this.baseStageVersion = baseStageVersion;
            this.instruction = instruction;
            this.existingArtifactJson = existingArtifactJson;
        }
    }
}
