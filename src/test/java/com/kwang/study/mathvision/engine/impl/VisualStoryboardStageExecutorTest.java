package com.kwang.study.mathvision.engine.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionContext;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionResult;
import com.kwang.study.mathvision.enums.StageEnum;
import com.kwang.study.mathvision.mapper.MathVisionArtifactMapper;
import com.kwang.study.mathvision.mapper.MathVisionVersionMapper;
import com.kwang.study.mathvision.pojo.MathVisionArtifact;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.pojo.MathVisionVersion;
import com.kwang.study.mathvision.workflow.model.KnowledgeGraph;
import com.kwang.study.mathvision.workflow.model.Narrative;
import com.kwang.study.mathvision.workflow.model.ProblemBundle;
import com.kwang.study.mathvision.workflow.model.StoryboardValidationReport;
import com.kwang.study.mathvision.workflow.node.VisualDesignNode;
import com.kwang.study.mathvision.workflow.validation.StoryboardValidationNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VisualStoryboardStageExecutorTest {

    @Test
    void waitsAfterVisualDesignAndOnlyValidatesWhenReviewIsRequested() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MathVisionArtifactMapper artifactMapper = mock(MathVisionArtifactMapper.class);
        MathVisionVersionMapper versionMapper = mock(MathVisionVersionMapper.class);
        VisualDesignNode visualDesignNode = mock(VisualDesignNode.class);
        StoryboardValidationNode validationNode = mock(StoryboardValidationNode.class);

        MathVisionTask task = MathVisionTask.builder()
                .id(61L)
                .currentVersion(2)
                .outputTarget("manim")
                .build();
        MathVisionVersion version = MathVisionVersion.builder()
                .taskId(61L)
                .version(2)
                .pnVersion(1)
                .rgVersion(1)
                .build();
        ProblemBundle bundle = new ProblemBundle();
        KnowledgeGraph graph = new KnowledgeGraph();
        Narrative narrative = new Narrative("讲解", new Narrative.Storyboard());
        when(versionMapper.findCurrent(61L)).thenReturn(version);
        when(artifactMapper.findByTaskStageVersion(61L, StageEnum.PROBLEM_NORMALIZATION.getCode(), 1))
                .thenReturn(artifact(objectMapper.writeValueAsString(bundle)));
        when(artifactMapper.findByTaskStageVersion(61L, StageEnum.REASONING_GRAPH.getCode(), 1))
                .thenReturn(artifact(objectMapper.writeValueAsString(graph)));
        when(visualDesignNode.run(
                eq(task), any(ProblemBundle.class), any(KnowledgeGraph.class),
                any(MathVisionStageExecutionContext.class)))
                .thenReturn(visualDesignResult(narrative, 2));

        VisualStoryboardStageExecutor executor = new VisualStoryboardStageExecutor(
                artifactMapper, versionMapper, objectMapper, visualDesignNode, validationNode);
        MathVisionStageExecutionResult designResult = executor.execute(
                MathVisionStageExecutionContext.builder()
                        .task(task)
                        .stage(StageEnum.VISUAL_STORYBOARD)
                        .build());

        assertFalse(designResult.isFailed());
        assertTrue(designResult.isWaitForUserDecision());
        JsonNode designJson = objectMapper.readTree(designResult.getResultJson());
        assertEquals("pending", designJson.path("qualityReview").path("status").asText());
        assertFalse(designJson.path("validationCompleted").asBoolean());
        verify(validationNode, times(0)).run(
                eq(task), any(ProblemBundle.class), any(KnowledgeGraph.class),
                any(Narrative.class), any(MathVisionStageExecutionContext.class));

        version.setVsVersion(1);
        when(artifactMapper.findByTaskStageVersion(61L, StageEnum.VISUAL_STORYBOARD.getCode(), 1))
                .thenReturn(artifact(designResult.getArtifactJson()));
        StoryboardValidationReport report = new StoryboardValidationReport();
        when(validationNode.run(
                eq(task), any(ProblemBundle.class), any(KnowledgeGraph.class),
                any(Narrative.class), any(MathVisionStageExecutionContext.class)))
                .thenReturn(validationResult(narrative, report, 1));

        MathVisionStageExecutionResult reviewResult = executor.execute(
                MathVisionStageExecutionContext.builder()
                        .task(task)
                        .stage(StageEnum.VISUAL_STORYBOARD)
                        .qualityReviewRequested(true)
                        .existingStageResultJson(designResult.getResultJson())
                        .build());

        assertTrue(reviewResult.isWaitForUserDecision());
        JsonNode reviewJson = objectMapper.readTree(reviewResult.getResultJson());
        assertTrue(reviewJson.path("validationCompleted").asBoolean());
        assertEquals("completed", reviewJson.path("qualityReview").path("status").asText());
        verify(visualDesignNode, times(1)).run(
                eq(task), any(ProblemBundle.class), any(KnowledgeGraph.class),
                any(MathVisionStageExecutionContext.class));
        verify(validationNode, times(1)).run(
                eq(task), any(ProblemBundle.class), any(KnowledgeGraph.class),
                any(Narrative.class), any(MathVisionStageExecutionContext.class));
    }

    private static MathVisionArtifact artifact(String json) {
        return MathVisionArtifact.builder().artifactJson(json).build();
    }

    private static VisualDesignNode.Result visualDesignResult(Narrative narrative, int apiCalls) throws Exception {
        Constructor<VisualDesignNode.Result> constructor = VisualDesignNode.Result.class
                .getDeclaredConstructor(Narrative.class, int.class, int.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(narrative, apiCalls, 1, "multi_scene");
    }

    private static StoryboardValidationNode.Result validationResult(
            Narrative narrative,
            StoryboardValidationReport report,
            int apiCalls) throws Exception {
        Constructor<StoryboardValidationNode.Result> constructor = StoryboardValidationNode.Result.class
                .getDeclaredConstructor(Narrative.class, StoryboardValidationReport.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(narrative, report, apiCalls);
    }
}
