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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
    @Mock private MathVisionStageExecutor executor;

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
                workflowSummaryService);
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
        verify(taskMapper).markWaitingConfirm(85L, "visual_storyboard");
        verify(taskMapper, never()).queueNextStage(any(), any());
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
                runningAuto, runningAuto, runningAuto, latestManual, latestManual);
        when(executorRegistry.find(StageEnum.REASONING_GRAPH)).thenReturn(Optional.of(executor));
        when(executor.execute(any())).thenReturn(MathVisionStageExecutionResult.builder().build());

        runner.runOneVisibleStage(84L);

        verify(taskMapper).markWaitingConfirm(84L, "reasoning_graph");
        verify(taskMapper, never()).queueNextStage(84L, "visual_storyboard");
    }
}
