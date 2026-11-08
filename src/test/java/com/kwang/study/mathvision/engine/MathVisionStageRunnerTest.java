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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MathVisionStageRunnerTest {

    @Mock private MathVisionTaskMapper taskMapper;
    @Mock private MathVisionArtifactMapper artifactMapper;
    @Mock private MathVisionStageResultMapper stageResultMapper;
    @Mock private MathVisionVersionMapper versionMapper;
    @Mock private MathVisionStageExecutorRegistry executorRegistry;
    @Mock private MathVisionTaskNotifier taskNotifier;
    @Mock private MathVisionWorkflowSummaryService workflowSummaryService;
    @Mock private MathVisionTaskExecutionRegistry executionRegistry;
    @Mock private MathVisionStageExecutor executor;
    @Mock private MathVisionStageCommitService commits;

    private MathVisionStageRunner runner;

    @BeforeEach
    void setUp() {
        runner = new MathVisionStageRunner(
                taskMapper,
                artifactMapper,
                stageResultMapper,
                versionMapper,
                executorRegistry,
                taskNotifier,
                workflowSummaryService,
                executionRegistry, commits);
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run(); return null;
        }).when(commits).commit(any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void restoresUserRevisionContextPersistsStageBaseVersionAndStopsForConfirmation() {
        MathVisionTask task = MathVisionTask.builder()
                .id(81L)
                .sessionId("session-81")
                .userId(9L)
                .mode("auto")
                .status("running")
                .currentStage("visual_storyboard")
                .currentVersion(3)
                .cancelRequested(false)
                .build();
        MathVisionVersion version = MathVisionVersion.builder()
                .taskId(81L)
                .version(3)
                .vsVersion(2)
                .branchStage("visual_storyboard")
                .changeSource("user_revision")
                .changeInstruction("增加辅助线并重做全部场景")
                .build();
        MathVisionArtifact baseline = MathVisionArtifact.builder()
                .taskId(81L)
                .stage("visual_storyboard")
                .version(2)
                .artifactJson("{\"target_description\":\"old storyboard\"}")
                .build();
        when(taskMapper.findById(81L)).thenReturn(task);
        when(versionMapper.findByTaskVersion(81L, 3)).thenReturn(version);
        when(artifactMapper.findByTaskStageVersion(81L, "visual_storyboard", 2)).thenReturn(baseline);
        when(executorRegistry.find(StageEnum.VISUAL_STORYBOARD)).thenReturn(Optional.of(executor));
        when(executor.execute(any())).thenReturn(MathVisionStageExecutionResult.builder()
                .artifactJson("{\"target_description\":\"revised storyboard\"}")
                .resultJson("{\"validationCompleted\":true}")
                .waitForUserDecision(true)
                .build());
        when(artifactMapper.findMaxVersion(81L, "visual_storyboard")).thenReturn(4);

        runner.runOneVisibleStage(81L);

        ArgumentCaptor<MathVisionStageExecutionContext> contextCaptor =
                ArgumentCaptor.forClass(MathVisionStageExecutionContext.class);
        verify(executor).execute(contextCaptor.capture());
        MathVisionStageExecutionContext context = contextCaptor.getValue();
        assertTrue(context.isUserRevision());
        assertEquals(2, context.getBaseStageVersion());
        assertEquals("增加辅助线并重做全部场景", context.getInstruction());
        assertEquals(baseline.getArtifactJson(), context.getExistingArtifactJson());

        ArgumentCaptor<MathVisionArtifact> artifactCaptor = ArgumentCaptor.forClass(MathVisionArtifact.class);
        verify(artifactMapper).insert(artifactCaptor.capture());
        assertEquals(2, artifactCaptor.getValue().getBaseVersion());
        assertEquals("user_revision", artifactCaptor.getValue().getChangeSource());
        verify(versionMapper).updateStagePointer(81L, 3, "visual_storyboard", 5);
        verify(taskMapper).markWaitingConfirm(81L, "visual_storyboard");
        verify(taskMapper, never()).queueNextStage(any(), any());
    }

    @Test
    void failedUserRevisionKeepsBaselinePointerForRetry() {
        MathVisionTask task = MathVisionTask.builder()
                .id(83L)
                .sessionId("session-83")
                .userId(9L)
                .mode("auto")
                .status("running")
                .currentStage("reasoning_graph")
                .currentVersion(4)
                .cancelRequested(false)
                .build();
        MathVisionVersion version = MathVisionVersion.builder()
                .taskId(83L)
                .version(4)
                .rgVersion(3)
                .branchStage("reasoning_graph")
                .changeSource("user_revision")
                .changeInstruction("修正 Q 点轨迹")
                .build();
        MathVisionArtifact baseline = MathVisionArtifact.builder()
                .id(31L)
                .taskId(83L)
                .stage("reasoning_graph")
                .version(3)
                .artifactJson("{\"nodes\":{\"Q\":{}}}")
                .build();
        when(taskMapper.findById(83L)).thenReturn(task);
        when(versionMapper.findByTaskVersion(83L, 4)).thenReturn(version);
        when(artifactMapper.findByTaskStageVersion(83L, "reasoning_graph", 3)).thenReturn(baseline);
        when(executorRegistry.find(StageEnum.REASONING_GRAPH)).thenReturn(Optional.of(executor));
        when(executor.execute(any())).thenReturn(MathVisionStageExecutionResult.builder()
                .artifactJson("{\"nodes\":{\"Q\":{\"invalid\":true}}}")
                .failed(true)
                .errorType("workflow_error")
                .errorMessage("revision validation failed")
                .build());

        runner.runOneVisibleStage(83L);

        verify(taskMapper).markFailed(
                83L, "reasoning_graph", "workflow_error", "revision validation failed");
        verify(artifactMapper, never()).findMaxVersion(83L, "reasoning_graph");
        verify(artifactMapper, never()).insert(any());
        verify(artifactMapper, never()).updateArtifactJson(any());
        verify(versionMapper, never()).updateStagePointer(any(), any(), any(), any());
    }

    @Test
    void automaticRerunUpdatesCurrentStageArtifactAndResultInPlace() {
        MathVisionTask task = MathVisionTask.builder()
                .id(82L)
                .sessionId("session-82")
                .userId(9L)
                .mode("auto")
                .status("running")
                .currentStage("visual_storyboard")
                .currentVersion(2)
                .cancelRequested(false)
                .build();
        MathVisionVersion version = MathVisionVersion.builder()
                .taskId(82L)
                .version(2)
                .vsVersion(4)
                .changeSource("initial_generation")
                .build();
        MathVisionArtifact artifact = MathVisionArtifact.builder()
                .id(44L)
                .taskId(82L)
                .stage("visual_storyboard")
                .version(4)
                .artifactJson("{\"target_description\":\"old\"}")
                .build();
        MathVisionStageResult stageResult = MathVisionStageResult.builder()
                .id(45L)
                .artifactId(44L)
                .stage("visual_storyboard")
                .version(4)
                .resultJson("{\"validationCompleted\":false}")
                .build();
        when(taskMapper.findById(82L)).thenReturn(task);
        when(versionMapper.findByTaskVersion(82L, 2)).thenReturn(version);
        when(artifactMapper.findByTaskStageVersion(82L, "visual_storyboard", 4)).thenReturn(artifact);
        when(stageResultMapper.findByArtifactId(44L)).thenReturn(stageResult);
        when(executorRegistry.find(StageEnum.VISUAL_STORYBOARD)).thenReturn(Optional.of(executor));
        when(executor.execute(any())).thenReturn(MathVisionStageExecutionResult.builder()
                .artifactJson("{\"target_description\":\"updated\"}")
                .resultJson("{\"validationCompleted\":true}")
                .changeSource("auto_fix")
                .changeSummary("system retry completed")
                .build());

        runner.runOneVisibleStage(82L);

        ArgumentCaptor<MathVisionArtifact> artifactCaptor = ArgumentCaptor.forClass(MathVisionArtifact.class);
        verify(artifactMapper).updateArtifactJson(artifactCaptor.capture());
        assertEquals(4, artifactCaptor.getValue().getVersion());
        assertEquals("{\"target_description\":\"updated\"}", artifactCaptor.getValue().getArtifactJson());
        assertEquals("auto_fix", artifactCaptor.getValue().getChangeSource());
        verify(stageResultMapper).updateResultJson(stageResult);
        assertEquals("{\"validationCompleted\":true}", stageResult.getResultJson());
        verify(artifactMapper, never()).findMaxVersion(82L, "visual_storyboard");
        verify(artifactMapper, never()).insert(any());
        verify(versionMapper, never()).updateStagePointer(82L, 2, "visual_storyboard", 4);
        verify(taskMapper).queueNextStage(82L, "code_generation");
    }

    @Test
    void qualityReviewResumesCurrentArtifactEvenWhenTaskVersionCameFromUserRevision() {
        MathVisionTask task = MathVisionTask.builder()
                .id(85L)
                .sessionId("session-85")
                .userId(9L)
                .mode("auto")
                .status("running")
                .currentStage("visual_storyboard")
                .currentVersion(6)
                .cancelRequested(false)
                .build();
        MathVisionVersion version = MathVisionVersion.builder()
                .taskId(85L)
                .version(6)
                .vsVersion(8)
                .branchStage("visual_storyboard")
                .changeSource("user_revision")
                .changeInstruction("修正轨迹")
                .build();
        MathVisionArtifact artifact = MathVisionArtifact.builder()
                .id(86L)
                .taskId(85L)
                .stage("visual_storyboard")
                .version(8)
                .artifactJson("{\"storyboard\":{}}")
                .build();
        MathVisionStageResult stageResult = MathVisionStageResult.builder()
                .id(87L)
                .artifactId(86L)
                .taskId(85L)
                .stage("visual_storyboard")
                .version(8)
                .resultJson("{\"qualityReview\":{\"status\":\"requested\"}}")
                .build();
        when(taskMapper.findById(85L)).thenReturn(task);
        when(versionMapper.findByTaskVersion(85L, 6)).thenReturn(version);
        when(stageResultMapper.findByTaskStageVersion(85L, "visual_storyboard", 8))
                .thenReturn(stageResult);
        when(artifactMapper.findByTaskStageVersion(85L, "visual_storyboard", 8))
                .thenReturn(artifact);
        when(stageResultMapper.findByArtifactId(86L)).thenReturn(stageResult);
        when(executorRegistry.find(StageEnum.VISUAL_STORYBOARD)).thenReturn(Optional.of(executor));
        when(executor.execute(any())).thenReturn(MathVisionStageExecutionResult.builder()
                .artifactJson("{\"storyboard\":{\"validated\":true}}")
                .resultJson("{\"qualityReview\":{\"status\":\"completed\"}}")
                .waitForUserDecision(true)
                .build());

        runner.runOneVisibleStage(85L);

        ArgumentCaptor<MathVisionStageExecutionContext> contextCaptor =
                ArgumentCaptor.forClass(MathVisionStageExecutionContext.class);
        verify(executor).execute(contextCaptor.capture());
        assertTrue(contextCaptor.getValue().isQualityReviewRequested());
        assertTrue(!contextCaptor.getValue().isUserRevision());
        verify(artifactMapper).updateArtifactJson(artifact);
        verify(artifactMapper, never()).insert(any());
        verify(stageResultMapper).updateResultJson(stageResult);
        verify(taskMapper).queueNextStage(85L, "code_generation");
        verify(taskMapper, never()).markWaitingConfirm(any(), any());
    }

    @ParameterizedTest
    @CsvSource({
            "VISUAL_STORYBOARD, code_generation",
            "CODE_GENERATION, render_result",
            "RENDER_RESULT, completed"
    })
    void autoModeAdvancesDespiteOptionalQualityReviewDecision(StageEnum stage, String nextStage) {
        MathVisionTask task = runningTask(90L, stage, "auto");
        task.setAutoQualityReview(false);
        doAnswer(invocation -> {
            invocation.getArgument(0, MathVisionArtifact.class).setId(900L);
            return null;
        }).when(artifactMapper).insert(any());
        when(taskMapper.findById(90L)).thenReturn(task);
        when(executorRegistry.find(stage)).thenReturn(Optional.of(executor));
        when(executor.execute(any())).thenReturn(MathVisionStageExecutionResult.builder()
                .artifactJson("{}")
                .resultJson("{\"qualityReview\":{\"status\":\"pending\"}}")
                .waitForUserDecision(true)
                .build());

        runner.runOneVisibleStage(task);

        if ("completed".equals(nextStage)) {
            verify(taskMapper).markCompleted(90L);
            verify(taskMapper, never()).queueNextStage(any(), any());
        } else {
            verify(taskMapper).queueNextStage(90L, nextStage);
            verify(taskMapper, never()).markCompleted(any());
        }
        verify(taskMapper, never()).markWaitingConfirm(any(), any());
        verify(executor, times(1)).execute(any());
        ArgumentCaptor<MathVisionStageExecutionContext> context = ArgumentCaptor.forClass(MathVisionStageExecutionContext.class);
        verify(executor).execute(context.capture());
        assertTrue(!context.getValue().isQualityReviewRequested());
        verify(artifactMapper).insert(any());
        verify(stageResultMapper).insert(any());
    }

    @ParameterizedTest
    @EnumSource(value = StageEnum.class, names = {"VISUAL_STORYBOARD", "CODE_GENERATION", "RENDER_RESULT"})
    void manualModeStillWaitsForOptionalQualityReviewDecision(StageEnum stage) {
        MathVisionTask task = runningTask(91L, stage, "manual");
        when(taskMapper.findById(91L)).thenReturn(task);
        when(executorRegistry.find(stage)).thenReturn(Optional.of(executor));
        when(executor.execute(any())).thenReturn(MathVisionStageExecutionResult.builder()
                .waitForUserDecision(true).build());

        runner.runOneVisibleStage(task);

        verify(taskMapper).markWaitingConfirm(91L, stage.getCode());
        verify(taskMapper, never()).queueNextStage(any(), any());
        verify(taskMapper, never()).markCompleted(any());
    }

    @ParameterizedTest
    @CsvSource({"manual, auto", "auto, manual"})
    void modeChangedDuringOptionalReviewStageUsesLatestMode(String initialMode, String latestMode) {
        MathVisionTask running = runningTask(92L, StageEnum.VISUAL_STORYBOARD, initialMode);
        when(taskMapper.findById(92L)).thenReturn(runningTask(92L, StageEnum.VISUAL_STORYBOARD, latestMode));
        when(executorRegistry.find(StageEnum.VISUAL_STORYBOARD)).thenReturn(Optional.of(executor));
        when(executor.execute(any())).thenReturn(MathVisionStageExecutionResult.builder()
                .waitForUserDecision(true).build());

        runner.runOneVisibleStage(running);

        if ("auto".equals(latestMode)) {
            verify(taskMapper).queueNextStage(92L, "code_generation");
            verify(taskMapper, never()).markWaitingConfirm(any(), any());
        } else {
            verify(taskMapper).markWaitingConfirm(92L, "visual_storyboard");
            verify(taskMapper, never()).queueNextStage(any(), any());
        }
    }

    @Test
    void automaticTaskContinuesAcrossAllStagesUntilCompleted() {
        AtomicReference<MathVisionTask> current = new AtomicReference<>(
                runningTask(93L, StageEnum.PROBLEM_NORMALIZATION, "auto"));
        MathVisionVersion version = MathVisionVersion.builder().taskId(93L).version(1).build();
        Map<String, MathVisionArtifact> artifacts = new HashMap<>();
        Map<String, MathVisionStageResult> results = new HashMap<>();
        when(taskMapper.findById(93L)).thenAnswer(ignored -> current.get());
        when(versionMapper.findByTaskVersion(93L, 1)).thenReturn(version);
        doAnswer(invocation -> {
            MathVisionArtifact artifact = invocation.getArgument(0);
            artifact.setId((long) artifacts.size() + 1); artifacts.put(artifact.getStage(), artifact); return null;
        }).when(artifactMapper).insert(any());
        when(artifactMapper.findByTaskStageVersion(org.mockito.ArgumentMatchers.eq(93L), any(), any()))
                .thenAnswer(invocation -> artifacts.get(invocation.getArgument(1)));
        doAnswer(invocation -> {
            String stage = invocation.getArgument(2); Integer pointer = invocation.getArgument(3);
            switch (stage) {
                case "problem_normalization": version.setPnVersion(pointer); break;
                case "reasoning_graph": version.setRgVersion(pointer); break;
                case "visual_storyboard": version.setVsVersion(pointer); break;
                case "code_generation": version.setCgVersion(pointer); break;
                case "render_result": version.setRrVersion(pointer); break;
            }
            return null;
        }).when(versionMapper).updateStagePointer(org.mockito.ArgumentMatchers.eq(93L), org.mockito.ArgumentMatchers.eq(1), any(), any());
        doAnswer(invocation -> {
            MathVisionStageResult result = invocation.getArgument(0); results.put(result.getStage(), result); return null;
        }).when(stageResultMapper).insert(any());
        when(stageResultMapper.findByTaskStageVersion(org.mockito.ArgumentMatchers.eq(93L), any(), any()))
                .thenAnswer(invocation -> results.get(invocation.getArgument(1)));
        when(stageResultMapper.findByArtifactId(any())).thenAnswer(invocation -> results.values().stream()
                .filter(result -> result.getArtifactId().equals(invocation.getArgument(0))).findFirst().orElse(null));
        for (StageEnum stage : StageEnum.values()) {
            if (stage == StageEnum.COMPLETED) continue;
            when(executorRegistry.find(stage)).thenReturn(Optional.of(executor));
        }
        when(executor.execute(any())).thenAnswer(invocation -> {
            MathVisionStageExecutionContext context = invocation.getArgument(0);
            return MathVisionStageExecutionResult.builder()
                    .artifactJson("{}")
                    .resultJson(context.isQualityReviewRequested()
                            ? "{\"qualityReview\":{\"status\":\"completed\"}}"
                            : "{\"qualityReview\":{\"status\":\"pending\"}}")
                    .waitForUserDecision(MathVisionStageQualityReview.supports(context.getStage())).build();
        });
        doAnswer(invocation -> {
            current.set(runningTask(93L, StageEnum.fromCode(invocation.getArgument(1)), "auto"));
            current.get().setStatus("queued");
            return null;
        }).when(taskMapper).queueNextStage(org.mockito.ArgumentMatchers.eq(93L), any());
        doAnswer(ignored -> { current.get().setStatus("completed"); return null; })
                .when(taskMapper).markCompleted(93L);

        for (int i = 0; i < 8; i++) {
            current.get().setStatus("running");
            runner.runOneVisibleStage(current.get());
            assertEquals(i == 7 ? "completed" : "queued", current.get().getStatus());
        }

        ArgumentCaptor<MathVisionStageExecutionContext> executions = ArgumentCaptor.forClass(MathVisionStageExecutionContext.class);
        verify(executor, times(8)).execute(executions.capture());
        assertEquals(java.util.List.of(false, false, false, true, false, true, false, true),
                executions.getAllValues().stream().map(MathVisionStageExecutionContext::isQualityReviewRequested)
                        .collect(java.util.stream.Collectors.toList()));
        verify(taskMapper).queueNextStage(93L, "reasoning_graph");
        verify(taskMapper, times(2)).queueNextStage(93L, "visual_storyboard");
        verify(taskMapper, times(2)).queueNextStage(93L, "code_generation");
        verify(taskMapper, times(2)).queueNextStage(93L, "render_result");
        verify(taskMapper, never()).markWaitingConfirm(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = StageEnum.class, names = {"VISUAL_STORYBOARD", "CODE_GENERATION", "RENDER_RESULT"})
    void automaticModeSchedulesReviewByDefaultAndPersistsItsRequest(StageEnum stage) {
        MathVisionTask task = runningTask(95L, stage, "auto");
        when(taskMapper.findById(95L)).thenReturn(task);
        when(executorRegistry.find(stage)).thenReturn(Optional.of(executor));
        when(executor.execute(any())).thenReturn(MathVisionStageExecutionResult.builder()
                .artifactJson("{}").resultJson("{\"qualityReview\":{\"status\":\"pending\"}}")
                .waitForUserDecision(true).build());
        doAnswer(invocation -> { invocation.getArgument(0, MathVisionArtifact.class).setId(950L); return null; })
                .when(artifactMapper).insert(any());

        runner.runOneVisibleStage(task);

        verify(taskMapper).queueNextStage(95L, stage.getCode());
        verify(taskMapper, never()).markWaitingConfirm(any(), any());
        verify(taskMapper, never()).markCompleted(any());
        ArgumentCaptor<MathVisionStageResult> result = ArgumentCaptor.forClass(MathVisionStageResult.class);
        verify(stageResultMapper).insert(result.capture());
        assertTrue(MathVisionStageQualityReview.isRequested(stage, result.getValue().getResultJson()));
    }

    @ParameterizedTest
    @CsvSource({"true, false", "false, true"})
    void generationUsesLatestAutoReviewSwitchWhenCommitting(boolean initial, boolean latest) {
        MathVisionTask task = runningTask(96L, StageEnum.VISUAL_STORYBOARD, "auto");
        task.setAutoQualityReview(initial);
        MathVisionTask current = runningTask(96L, StageEnum.VISUAL_STORYBOARD, "auto");
        current.setAutoQualityReview(latest);
        when(taskMapper.findById(96L)).thenReturn(current);
        when(executorRegistry.find(StageEnum.VISUAL_STORYBOARD)).thenReturn(Optional.of(executor));
        when(executor.execute(any())).thenReturn(MathVisionStageExecutionResult.builder()
                .artifactJson("{}").resultJson("{}").waitForUserDecision(true).build());

        runner.runOneVisibleStage(task);

        verify(taskMapper).queueNextStage(96L, latest ? "visual_storyboard" : "code_generation");
    }

    @Test
    void automaticStageFailureStillStopsBeforeDownstreamStages() {
        MathVisionTask task = runningTask(94L, StageEnum.CODE_GENERATION, "auto");
        when(taskMapper.findById(94L)).thenReturn(task);
        when(executorRegistry.find(StageEnum.CODE_GENERATION)).thenReturn(Optional.of(executor));
        when(executor.execute(any())).thenReturn(MathVisionStageExecutionResult.builder()
                .failed(true).errorType("workflow_error").errorMessage("generation failed")
                .waitForUserDecision(true).build());

        runner.runOneVisibleStage(task);

        verify(taskMapper).markFailed(94L, "code_generation", "workflow_error", "generation failed");
        verify(taskMapper, never()).queueNextStage(any(), any());
        verify(taskMapper, never()).markCompleted(any());
    }

    private MathVisionTask runningTask(Long taskId, StageEnum stage, String mode) {
        return MathVisionTask.builder().id(taskId).sessionId("session-" + taskId).userId(9L)
                .mode(mode).status("running").currentStage(stage.getCode()).currentVersion(1)
                .cancelRequested(false).build();
    }

    @Test
    void modeChangedToManualDuringStageStopsBeforeNextStage() {
        MathVisionTask runningAuto = MathVisionTask.builder()
                .id(84L)
                .sessionId("session-84")
                .userId(9L)
                .mode("auto")
                .status("running")
                .currentStage("reasoning_graph")
                .cancelRequested(false)
                .build();
        MathVisionTask latestManual = MathVisionTask.builder()
                .id(84L)
                .sessionId("session-84")
                .userId(9L)
                .mode("manual")
                .status("running")
                .currentStage("reasoning_graph")
                .cancelRequested(false)
                .build();
        when(taskMapper.findById(84L)).thenReturn(
                runningAuto, latestManual, latestManual);
        when(executorRegistry.find(StageEnum.REASONING_GRAPH)).thenReturn(Optional.of(executor));
        when(executor.execute(any())).thenReturn(MathVisionStageExecutionResult.builder().build());

        runner.runOneVisibleStage(84L);

        verify(taskMapper).markWaitingConfirm(84L, "reasoning_graph");
        verify(taskMapper, never()).queueNextStage(84L, "visual_storyboard");
    }
}
