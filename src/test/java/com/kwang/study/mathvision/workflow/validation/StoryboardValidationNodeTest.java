package com.kwang.study.mathvision.workflow.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionContext;
import com.kwang.study.mathvision.enums.StageEnum;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.workflow.ai.MathVisionAiChatService;
import com.kwang.study.mathvision.workflow.model.AiMessage;
import com.kwang.study.mathvision.workflow.model.KnowledgeGraph;
import com.kwang.study.mathvision.workflow.model.Narrative;
import com.kwang.study.mathvision.workflow.model.Narrative.Storyboard;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardConstraint;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardCoordinateBounds;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardCoordinateBoundsAxis;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardObject;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardPlacement;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardPlacementAxis;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardScene;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardStyle;
import com.kwang.study.mathvision.workflow.model.ProblemBundle;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoryboardValidationNodeTest {

    @Test
    void retriesCleanupWithConversationHistoryAfterSceneCountMismatch() {
        ObjectMapper objectMapper = new ObjectMapper();
        MathVisionAiChatService aiChatService = mock(MathVisionAiChatService.class);
        StoryboardValidationNode node = new StoryboardValidationNode(
                aiChatService, objectMapper, new MathVisionModelCatalog());

        MathVisionTask task = MathVisionTask.builder().id(7L).outputTarget("manim").build();
        ProblemBundle bundle = new ProblemBundle();
        bundle.setId("problem");
        bundle.setTitle("Test problem");
        bundle.setStatement("Test problem");
        bundle.setInputMode("problem");
        bundle.setSceneMode("2d");
        KnowledgeGraph graph = new KnowledgeGraph("", new LinkedHashMap<>(), new LinkedHashMap<>(), List.of());

        Storyboard original = storyboard("scene_1");
        Narrative narrative = new Narrative("Test target", original);
        JsonNode mismatched = objectMapper.valueToTree(storyboard("scene_1", "scene_2"));
        JsonNode corrected = objectMapper.valueToTree(storyboard("scene_1"));
        when(aiChatService.requestJson(eq(task), any(), anyString()))
                .thenReturn(mismatched, corrected);

        StoryboardValidationNode.Result result = node.run(
                task,
                bundle,
                graph,
                narrative,
                MathVisionStageExecutionContext.builder()
                        .task(task)
                        .stage(StageEnum.VISUAL_STORYBOARD)
                        .build());

        assertTrue(result.getReport().isPassed());
        assertEquals(2, result.getApiCalls());
        assertEquals(1, result.getNarrative().getStoryboard().getScenes().size());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiChatService, times(2)).requestJson(eq(task), messagesCaptor.capture(), anyString());
        List<AiMessage> retryMessages = messagesCaptor.getAllValues().get(1);
        assertTrue(retryMessages.stream().anyMatch(message -> "assistant".equals(message.getRole())));
        assertTrue(retryMessages.get(retryMessages.size() - 1).getParts().stream()
                .anyMatch(part -> part.getText() != null
                        && part.getText().contains("changed the number of scenes")));
    }

    @Test
    void resolvesRelativePlacementAndReportsOnlyRealLayoutOverlaps() throws Exception {
        StoryboardValidationNode node = new StoryboardValidationNode(
                mock(MathVisionAiChatService.class), new ObjectMapper(), new MathVisionModelCatalog());
        Storyboard storyboard = storyboard("scene_1");
        StoryboardCoordinateBounds bounds = new StoryboardCoordinateBounds();
        bounds.setX(new StoryboardCoordinateBoundsAxis(-4.0D, 4.0D));
        bounds.setY(new StoryboardCoordinateBoundsAxis(-3.0D, 3.0D));
        bounds.setPadding(0.1D);
        storyboard.setCoordinateBounds(bounds);

        StoryboardObject anchor = placedObject("A", "point", 0.0D, 0.0D);
        StoryboardObject attachedLabel = placedObject("L", "label", 0.0D, 0.0D);
        attachedLabel.getPlacement().setPositioning(StoryboardPlacement.POSITIONING_RELATIVE);
        StoryboardConstraint attachment = new StoryboardConstraint();
        attachment.setDomain("attachment");
        attachment.setRelation("label_for");
        attachment.setRefs(Map.of("label", "L", "anchor", "A"));
        attachedLabel.setConstraints(List.of(attachment));
        StoryboardObject collidingLabel = placedObject("M", "label", 0.0D, 0.0D);

        StoryboardScene mergedScene = storyboard.getScenes().get(0);
        mergedScene.setLayoutGoal("Keep labels readable");
        mergedScene.setPersistentObjects(List.of(anchor, attachedLabel, collidingLabel));
        List<String> issues = new ArrayList<>();
        Method method = StoryboardValidationNode.class.getDeclaredMethod(
                "validateSceneLayout", String.class, Storyboard.class, StoryboardScene.class, List.class);
        method.setAccessible(true);
        method.invoke(node, "scene 1 (scene_1)", storyboard, mergedScene, issues);

        assertTrue(issues.stream().anyMatch(issue ->
                issue.contains("text objects 'L' and 'M' overlap")
                        || issue.contains("text objects 'M' and 'L' overlap")));
        assertTrue(issues.stream().noneMatch(issue ->
                issue.contains("text object 'L' overlaps object 'A'")));

        Method summaryMethod = StoryboardValidationNode.class.getDeclaredMethod(
                "buildChainSummary", KnowledgeGraph.class, Storyboard.class);
        summaryMethod.setAccessible(true);
        String summary = (String) summaryMethod.invoke(node, null, storyboard);
        assertTrue(summary.contains("goal: Explain scene_1"));
        assertTrue(summary.contains("layout: Keep labels readable"));
    }

    private Storyboard storyboard(String... sceneIds) {
        Storyboard storyboard = new Storyboard();
        storyboard.setContinuityPlan("Keep continuity");
        storyboard.setGlobalVisualRules(new ArrayList<>());
        storyboard.setObjectRegistry(new ArrayList<>());
        List<StoryboardScene> scenes = new ArrayList<>();
        for (String sceneId : sceneIds) {
            StoryboardScene scene = new StoryboardScene();
            scene.setSceneId(sceneId);
            scene.setTitle("Scene " + sceneId);
            scene.setGoal("Explain " + sceneId);
            scene.setEnteringObjects(new ArrayList<>());
            scene.setPersistentObjects(new ArrayList<>());
            scene.setExitingObjects(new ArrayList<>());
            scene.setActions(new ArrayList<>());
            scene.setConstraints(new ArrayList<>());
            scene.setStepRefs(new ArrayList<>());
            scenes.add(scene);
        }
        storyboard.setScenes(scenes);
        return storyboard;
    }

    private StoryboardObject placedObject(String id, String kind, double x, double y) {
        StoryboardPlacementAxis xAxis = new StoryboardPlacementAxis();
        xAxis.setValue(x);
        StoryboardPlacementAxis yAxis = new StoryboardPlacementAxis();
        yAxis.setValue(y);
        StoryboardPlacement placement = new StoryboardPlacement();
        placement.setPositioning(StoryboardPlacement.POSITIONING_ABSOLUTE);
        placement.setX(xAxis);
        placement.setY(yAxis);
        StoryboardObject object = new StoryboardObject();
        object.setId(id);
        object.setKind(kind);
        object.setContent(id);
        object.setPlacement(placement);
        if ("label".equals(kind)) {
            StoryboardStyle style = new StoryboardStyle();
            style.setFontSize(36.0D);
            object.setStyle(style);
        }
        return object;
    }
}
