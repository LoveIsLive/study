package com.kwang.study.mathvision.engine.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionContext;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionResult;
import com.kwang.study.mathvision.enums.StageEnum;
import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.mapper.MathVisionArtifactMapper;
import com.kwang.study.mathvision.mapper.MathVisionVersionMapper;
import com.kwang.study.mathvision.pojo.MathVisionArtifact;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.pojo.MathVisionVersion;
import com.kwang.study.mathvision.workflow.model.CodeEvaluationResult;
import com.kwang.study.mathvision.workflow.model.CodeFixResult;
import com.kwang.study.mathvision.workflow.model.CodeResult;
import com.kwang.study.mathvision.workflow.model.Narrative;
import com.kwang.study.mathvision.workflow.model.ProblemBundle;
import com.kwang.study.mathvision.workflow.model.StageGenerationRequest;
import com.kwang.study.mathvision.workflow.node.CodeEvaluationNode;
import com.kwang.study.mathvision.workflow.node.CodeFixNode;
import com.kwang.study.mathvision.workflow.node.CodeGenerationNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeGenerationStageExecutorTest {

    @Test
    void waitsAfterGenerationThenRunsEvaluationAndFixWithoutAnotherUserPause() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MathVisionArtifactMapper artifactMapper = mock(MathVisionArtifactMapper.class);
        MathVisionVersionMapper versionMapper = mock(MathVisionVersionMapper.class);
        CodeGenerationNode codeGenerationNode = mock(CodeGenerationNode.class);
        CodeEvaluationNode codeEvaluationNode = mock(CodeEvaluationNode.class);
        CodeFixNode codeFixNode = mock(CodeFixNode.class);

        MathVisionTask task = MathVisionTask.builder()
                .id(71L)
                .currentVersion(2)
                .outputTarget("manim")
                .build();
        MathVisionVersion version = MathVisionVersion.builder()
                .taskId(71L)
                .version(2)
                .pnVersion(1)
                .vsVersion(1)
                .build();
        when(versionMapper.findCurrent(71L)).thenReturn(version);
        when(artifactMapper.findByTaskStageVersion(71L, StageEnum.PROBLEM_NORMALIZATION.getCode(), 1))
                .thenReturn(artifact(objectMapper.writeValueAsString(new ProblemBundle())));
        when(artifactMapper.findByTaskStageVersion(71L, StageEnum.VISUAL_STORYBOARD.getCode(), 1))
                .thenReturn(artifact(objectMapper.writeValueAsString(new Narrative())));

        CodeResult codeResult = new CodeResult();
        codeResult.setGeneratedCode("from manim import *\nclass MainScene(Scene):\n    def construct(self):\n        pass\n");
        codeResult.setSceneName("MainScene");
        codeResult.setOutputTarget("manim");
        when(codeGenerationNode.run(eq(task), any(ProblemBundle.class), any(Narrative.class),
                any(StageGenerationRequest.class), any(MathVisionStageExecutionContext.class)))
                .thenThrow(new IllegalStateException("temporary malformed code response"))
                .thenReturn(codeGenerationResult(codeResult, 1));

        CodeEvaluationResult evaluation = new CodeEvaluationResult();
        evaluation.setApprovedForRender(false);
        evaluation.setSceneName("MainScene");
        evaluation.setGateReason("Code review still recommends revisions");
        when(codeEvaluationNode.run(eq(task), any(ProblemBundle.class), any(Narrative.class),
                any(CodeResult.class), anyInt(), anyBoolean(), any(MathVisionStageExecutionContext.class)))
                .thenReturn(codeEvaluationResult(evaluation, 1));

        CodeFixResult fixResult = new CodeFixResult();
        fixResult.setApplied(true);
        fixResult.setOutcome(CodeFixResult.FixOutcome.APPLIED_WITH_ISSUES);
        fixResult.setFixedGeneratedCode(codeResult.getGeneratedCode());
        when(codeFixNode.run(eq(task), any(), any(MathVisionStageExecutionContext.class), anyList()))
                .thenReturn(codeFixResult(fixResult, 1));

        MathVisionModelCatalog modelCatalog = new MathVisionModelCatalog();
        modelCatalog.getWorkflow().setCodeGenerationRetryDelayMillis(0L);
        modelCatalog.getWorkflow().setCodeEvaluationMaxRetries(1);
        CodeGenerationStageExecutor executor = new CodeGenerationStageExecutor(
                artifactMapper,
                versionMapper,
                objectMapper,
                codeGenerationNode,
                codeEvaluationNode,
                codeFixNode,
                modelCatalog);

        MathVisionStageExecutionResult generationResult = executor.execute(
                MathVisionStageExecutionContext.builder()
                        .task(task)
                        .stage(StageEnum.CODE_GENERATION)
                        .build());

        assertFalse(generationResult.isFailed());
        assertTrue(generationResult.isWaitForUserDecision());
        JsonNode generationJson = objectMapper.readTree(generationResult.getResultJson());
        assertTrue(generationJson.path("codeGenerationAttempts").asInt() == 2);
        assertTrue(generationJson.path("codeGenerationRetryFailures").size() == 1);
        assertTrue("pending".equals(generationJson.path("qualityReview").path("status").asText()));
        verify(codeEvaluationNode, times(0)).run(eq(task), any(ProblemBundle.class), any(Narrative.class),
                any(CodeResult.class), anyInt(), anyBoolean(), any(MathVisionStageExecutionContext.class));
        verify(codeFixNode, times(0)).run(
                eq(task), any(), any(MathVisionStageExecutionContext.class), anyList());

        version.setCgVersion(1);
        when(artifactMapper.findByTaskStageVersion(71L, StageEnum.CODE_GENERATION.getCode(), 1))
                .thenReturn(artifact(generationResult.getArtifactJson()));
        MathVisionStageExecutionResult reviewResult = executor.execute(
                MathVisionStageExecutionContext.builder()
                        .task(task)
                        .stage(StageEnum.CODE_GENERATION)
                        .qualityReviewRequested(true)
                        .existingStageResultJson(generationResult.getResultJson())
                        .build());

        assertFalse(reviewResult.isFailed());
        assertTrue(reviewResult.isWaitForUserDecision());
        JsonNode resultJson = objectMapper.readTree(reviewResult.getResultJson());
        assertFalse(resultJson.path("codeEvaluationApproved").asBoolean());
        assertTrue(resultJson.path("codeEvaluationWarning").asBoolean());
        assertTrue(resultJson.path("codeGenerationAttempts").asInt() == 2);
        assertTrue(resultJson.path("codeGenerationRetryFailures").size() == 1);
        assertTrue(resultJson.path("codeEvaluationMaxRetries").asInt() == 1);
        assertTrue("completed".equals(resultJson.path("qualityReview").path("status").asText()));
        verify(codeGenerationNode, times(2)).run(
                eq(task), any(ProblemBundle.class), any(Narrative.class),
                any(StageGenerationRequest.class), any(MathVisionStageExecutionContext.class));
        verify(codeEvaluationNode, times(2)).run(eq(task), any(ProblemBundle.class), any(Narrative.class),
                any(CodeResult.class), anyInt(), anyBoolean(), any(MathVisionStageExecutionContext.class));
        verify(codeFixNode, times(1)).run(
                eq(task), any(), any(MathVisionStageExecutionContext.class), anyList());
    }

    private static MathVisionArtifact artifact(String json) {
        return MathVisionArtifact.builder().artifactJson(json).build();
    }

    private static CodeGenerationNode.Result codeGenerationResult(CodeResult result, int apiCalls) throws Exception {
        Constructor<CodeGenerationNode.Result> constructor =
                CodeGenerationNode.Result.class.getDeclaredConstructor(CodeResult.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(result, apiCalls);
    }

    private static CodeEvaluationNode.Result codeEvaluationResult(
            CodeEvaluationResult result, int apiCalls) throws Exception {
        Constructor<CodeEvaluationNode.Result> constructor =
                CodeEvaluationNode.Result.class.getDeclaredConstructor(CodeEvaluationResult.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(result, apiCalls);
    }

    private static CodeFixNode.Result codeFixResult(CodeFixResult result, int apiCalls) throws Exception {
        Constructor<CodeFixNode.Result> constructor =
                CodeFixNode.Result.class.getDeclaredConstructor(CodeFixResult.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(result, apiCalls);
    }
}
