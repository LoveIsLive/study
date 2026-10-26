package com.kwang.study.mathvision.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.mathvision.enums.StageEnum;
import com.kwang.study.mathvision.mapper.MathVisionArtifactMapper;
import com.kwang.study.mathvision.mapper.MathVisionStageResultMapper;
import com.kwang.study.mathvision.mapper.MathVisionVersionMapper;
import com.kwang.study.mathvision.pojo.MathVisionArtifact;
import com.kwang.study.mathvision.pojo.MathVisionStageResult;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.pojo.MathVisionVersion;
import com.kwang.study.mathvision.workflow.model.CodeResult;
import com.kwang.study.mathvision.workflow.model.RenderResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MathVisionFinalCodeArtifactServiceTest {

    @Test
    void writesFinalRenderedCodeIntoCurrentCodeStageVersionAndPreservesReviewResult() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MathVisionArtifactMapper artifactMapper = mock(MathVisionArtifactMapper.class);
        MathVisionStageResultMapper stageResultMapper = mock(MathVisionStageResultMapper.class);
        MathVisionVersionMapper versionMapper = mock(MathVisionVersionMapper.class);
        MathVisionFinalCodeArtifactService service = new MathVisionFinalCodeArtifactService(
                artifactMapper, stageResultMapper, versionMapper, objectMapper);

        MathVisionTask task = MathVisionTask.builder()
                .id(91L)
                .sessionId("session-91")
                .userId(9L)
                .currentVersion(4)
                .build();
        MathVisionVersion taskVersion = MathVisionVersion.builder()
                .taskId(91L)
                .version(4)
                .cgVersion(2)
                .build();
        when(versionMapper.findByTaskVersion(91L, 4)).thenReturn(taskVersion);

        CodeResult originalCode = new CodeResult();
        originalCode.setGeneratedCode("old code");
        originalCode.setSceneName("MainScene");
        originalCode.setArtifactName("MainScene");
        originalCode.setArtifactFormat("python");
        originalCode.setOutputTarget("manim");
        MathVisionArtifact currentArtifact = MathVisionArtifact.builder()
                .id(101L)
                .taskId(91L)
                .sessionId("session-91")
                .userId(9L)
                .stage(StageEnum.CODE_GENERATION.getCode())
                .version(2)
                .artifactJson(objectMapper.writeValueAsString(originalCode))
                .build();
        when(artifactMapper.findByTaskStageVersion(91L, StageEnum.CODE_GENERATION.getCode(), 2))
                .thenReturn(currentArtifact);
        MathVisionStageResult currentResult = MathVisionStageResult.builder()
                .id(301L)
                .artifactId(101L)
                .taskId(91L)
                .stage(StageEnum.CODE_GENERATION.getCode())
                .version(2)
                .resultJson("{\"codeEvaluationApproved\":false,\"codeEvaluationWarning\":true}")
                .build();
        when(stageResultMapper.findByArtifactId(101L)).thenReturn(currentResult);
        doAnswer(invocation -> { invocation.getArgument(0, MathVisionArtifact.class).setId(202L); return null; })
                .when(artifactMapper).insert(any());

        RenderResult renderResult = new RenderResult();
        renderResult.setSuccess(true);
        renderResult.setSceneName("MainScene");
        renderResult.setOutputTarget("manim");
        renderResult.setFinalGeneratedCode("from manim import *\nclass MainScene(Scene):\n"
                + "    def construct(self):\n        self.wait(1)\n");

        MathVisionFinalCodeArtifactService.WritebackResult result =
                service.persistFinalCode(task, renderResult);

        assertTrue(result.isUpdated());
        assertEquals(2, result.getPreviousVersion());
        assertEquals(3, result.getFinalVersion());

        ArgumentCaptor<MathVisionArtifact> artifactCaptor = ArgumentCaptor.forClass(MathVisionArtifact.class);
        verify(artifactMapper).insert(artifactCaptor.capture());
        MathVisionArtifact updatedArtifact = artifactCaptor.getValue();
        assertEquals(3, updatedArtifact.getVersion());
        assertEquals("auto_fix", updatedArtifact.getChangeSource());
        CodeResult insertedCode = objectMapper.readValue(updatedArtifact.getArtifactJson(), CodeResult.class);
        assertEquals(renderResult.getFinalGeneratedCode(), insertedCode.getGeneratedCode());

        ArgumentCaptor<MathVisionStageResult> resultCaptor = ArgumentCaptor.forClass(MathVisionStageResult.class);
        verify(stageResultMapper).insert(resultCaptor.capture());
        JsonNode copiedResult = objectMapper.readTree(resultCaptor.getValue().getResultJson());
        assertTrue(copiedResult.path("codeEvaluationWarning").asBoolean());
        assertTrue(copiedResult.path("finalCodeUpdatedFromRender").asBoolean());
        assertEquals(3, copiedResult.path("finalCodeStageVersion").asInt());
        verify(artifactMapper, never()).updateArtifactJson(any());
        verify(versionMapper).updateStagePointer(91L, 4, "code_generation", 3);
    }

    @Test
    void refusesToOverwriteCurrentCodeWhenRenderedCandidateDropsScenes() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MathVisionArtifactMapper artifactMapper = mock(MathVisionArtifactMapper.class);
        MathVisionStageResultMapper stageResultMapper = mock(MathVisionStageResultMapper.class);
        MathVisionVersionMapper versionMapper = mock(MathVisionVersionMapper.class);
        MathVisionFinalCodeArtifactService service = new MathVisionFinalCodeArtifactService(
                artifactMapper, stageResultMapper, versionMapper, objectMapper);

        MathVisionTask task = MathVisionTask.builder()
                .id(92L)
                .sessionId("session-92")
                .userId(9L)
                .currentVersion(4)
                .build();
        when(versionMapper.findByTaskVersion(92L, 4)).thenReturn(MathVisionVersion.builder()
                .taskId(92L)
                .version(4)
                .cgVersion(2)
                .build());

        CodeResult originalCode = new CodeResult();
        originalCode.setOutputTarget("manim");
        originalCode.setGeneratedCode(String.join("\n",
                "from manim import *",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        self.scene_1()",
                "        self.scene_2()",
                "    def scene_1(self):",
                "        self.wait(1)",
                "    def scene_2(self):",
                "        self.wait(1)"));
        MathVisionArtifact currentArtifact = MathVisionArtifact.builder()
                .id(102L)
                .taskId(92L)
                .stage(StageEnum.CODE_GENERATION.getCode())
                .version(2)
                .artifactJson(objectMapper.writeValueAsString(originalCode))
                .build();
        when(artifactMapper.findByTaskStageVersion(92L, StageEnum.CODE_GENERATION.getCode(), 2))
                .thenReturn(currentArtifact);

        RenderResult renderResult = new RenderResult();
        renderResult.setSuccess(true);
        renderResult.setOutputTarget("manim");
        renderResult.setFinalGeneratedCode(String.join("\n",
                "from manim import *",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        self.wait(1)"));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.persistFinalCode(task, renderResult));

        assertTrue(error.getMessage().contains("scene_1"));
        verify(artifactMapper, never()).updateArtifactJson(any(MathVisionArtifact.class));
    }
}
