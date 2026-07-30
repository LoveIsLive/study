package com.kwang.study.mathvision.workflow.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionContext;
import com.kwang.study.mathvision.enums.StageEnum;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.workflow.ai.MathVisionAiChatService;
import com.kwang.study.mathvision.workflow.model.AiMessage;
import com.kwang.study.mathvision.workflow.model.KnowledgeGraph;
import com.kwang.study.mathvision.workflow.model.KnowledgeNode;
import com.kwang.study.mathvision.workflow.model.Narrative;
import com.kwang.study.mathvision.workflow.model.Narrative.Storyboard;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardAction;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardCoordinateBounds;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardCoordinateBoundsAxis;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardObject;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardScene;
import com.kwang.study.mathvision.workflow.model.ProblemBundle;
import com.kwang.study.mathvision.workflow.model.VisualDesignMode;
import com.kwang.study.mathvision.workflow.model.VisualDesignRequest;
import com.kwang.study.mathvision.workflow.prompt.ToolSchemas;
import com.kwang.study.mathvision.workflow.prompt.VisualDesignPrompts;
import com.kwang.study.mathvision.workflow.util.TargetDescriptionBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VisualDesignNodeTest {

    @Mock private MathVisionAiChatService aiChatService;

    private ObjectMapper objectMapper;
    private VisualDesignNode node;
    private MathVisionTask task;
    private ProblemBundle bundle;
    private MathVisionStageExecutionContext context;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        node = new VisualDesignNode(aiChatService, objectMapper, new MathVisionModelCatalog());
        task = MathVisionTask.builder()
                .id(41L)
                .userId(7L)
                .outputTarget("manim")
                .build();
        bundle = new ProblemBundle();
        bundle.setId("demo");
        bundle.setTitle("示例题");
        bundle.setStatement("证明辅助线关系");
        bundle.setSceneMode("2d");
        context = MathVisionStageExecutionContext.builder()
                .task(task)
                .stage(StageEnum.VISUAL_STORYBOARD)
                .build();
    }

    @Test
    void existingRunMethodKeepsInitialGenerationPromptAndSchema() {
        KnowledgeGraph graph = graph("step_1", "展示初始图形");
        when(aiChatService.requestJson(eq(task), anyList(), anyString()))
                .thenReturn(scenePayload(
                        "初始场景",
                        List.of(),
                        List.of("A"),
                        Map.of("A", "A")));

        VisualDesignNode.Result result = node.run(task, bundle, graph, context);

        assertEquals(1, result.getApiCalls());
        assertEquals("初始场景", result.getNarrative().getStoryboard().getScenes().get(0).getTitle());

        ArgumentCaptor<List<AiMessage>> messagesCaptor = messageCaptor();
        ArgumentCaptor<String> schemaCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiChatService).requestJson(eq(task), messagesCaptor.capture(), schemaCaptor.capture());

        List<AiMessage> messages = messagesCaptor.getValue();
        assertEquals(3, messages.size());
        String targetDescription = TargetDescriptionBuilder.build(bundle, graph, null);
        String solutionChain = TargetDescriptionBuilder.buildSolutionChain(graph, null);
        assertEquals(
                VisualDesignPrompts.buildRulesPrompt("manim", "2d"),
                messageText(messages.get(0)));
        assertEquals(
                VisualDesignPrompts.buildFixedContextPrompt(
                        bundle, targetDescription, "manim", solutionChain, "2d"),
                messageText(messages.get(1)));
        assertFalse(messageText(messages.get(0)).contains("Storyboard user-revision mode"));
        assertFalse(messageText(messages.get(1)).contains("Operation mode: user_revision"));
        assertFalse(messageText(messages.get(2)).contains("User-requested storyboard revision"));
        assertEquals(
                ToolSchemas.sceneDesign("manim", "2d"),
                schemaCaptor.getValue());
        assertFalse(schemaCaptor.getValue().contains("updated_objects"));
    }

    @Test
    void userRevisionRevisesCompleteArtifactInOneCall() {
        KnowledgeGraph graph = graph(
                new KnowledgeNode("step_1", "Show the initial diagram", 0),
                new KnowledgeNode("step_2", "Add the auxiliary construction", 1));
        Narrative existing = existingNarrative();
        VisualDesignRequest request = VisualDesignRequest.builder()
                .mode(VisualDesignMode.USER_REVISION)
                .existingNarrative(existing)
                .instruction("Add auxiliary point C in the second scene and emphasize point B.")
                .baseStageVersion(4)
                .build();

        ObjectNode revisedPayload = objectMapper.valueToTree(existing.getStoryboard());
        ArrayNode scenes = (ArrayNode) revisedPayload.get("scenes");
        ((ObjectNode) scenes.get(0)).put("title", "Revised first scene");
        ((ObjectNode) scenes.get(1)).put("title", "Revised second scene");
        ArrayNode registry = (ArrayNode) revisedPayload.get("object_registry");
        ((ObjectNode) registry.get(0)).put("content", "Revised A");
        ((ObjectNode) registry.get(1)).put("content", "Emphasized B");
        registry.add(canonicalObjectJson("C", "Auxiliary C"));
        ((ArrayNode) scenes.get(1).get("entering_objects")).add(objectPatchJson("C"));
        when(aiChatService.requestJson(eq(task), anyList(), anyString()))
                .thenReturn(revisedPayload);

        VisualDesignNode.Result result = node.run(task, bundle, graph, request, context);

        assertEquals(1, result.getApiCalls());
        Narrative revised = result.getNarrative();
        assertEquals("Revised first scene", revised.getStoryboard().getScenes().get(0).getTitle());
        assertEquals("Revised second scene", revised.getStoryboard().getScenes().get(1).getTitle());
        assertEquals(3, revised.getStoryboard().getObjectRegistry().size());
        assertEquals("Revised A", registryObject(revised, "A").getContent());
        assertEquals("Emphasized B", registryObject(revised, "B").getContent());
        assertEquals("Auxiliary C", registryObject(revised, "C").getContent());

        ArgumentCaptor<List<AiMessage>> messagesCaptor = messageCaptor();
        ArgumentCaptor<String> schemaCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiChatService).requestJson(
                eq(task), messagesCaptor.capture(), schemaCaptor.capture());

        List<AiMessage> messages = messagesCaptor.getValue();
        assertEquals(3, messages.size());
        String revisionRules = messageText(messages.get(0));
        assertTrue(revisionRules.startsWith(VisualDesignPrompts.buildRulesPrompt("manim", "2d")));
        assertTrue(revisionRules.contains("Complete storyboard user-revision mode"));
        assertTrue(revisionRules.contains("overrides only the scene-level response granularity"));
        assertTrue(revisionRules.contains("do not return the original per-scene {scene, new_objects} response"));
        assertFalse(revisionRules.contains("Storyboard validation repair rules"));
        assertTrue(messageText(messages.get(1)).contains("Operation mode: user_revision"));
        assertTrue(messageText(messages.get(1)).contains("Base visual_storyboard stage version: 4"));
        String userPrompt = messageText(messages.get(2));
        assertTrue(userPrompt.contains("Add auxiliary point C in the second scene"));
        assertTrue(userPrompt.contains("Complete existing Narrative artifact"));
        assertTrue(userPrompt.contains("continuity_plan"));
        assertTrue(userPrompt.contains("object_registry"));
        assertTrue(userPrompt.contains("coordinate_bounds"));
        assertEquals(ToolSchemas.storyboard("manim", "2d"), schemaCaptor.getValue());
        assertTrue(schemaCaptor.getValue().contains("write_storyboard"));
    }

    @Test
    void carriesAcceptedRetryPromptAndRegistryConstraintsIntoNextScene() {
        KnowledgeGraph graph = graph(
                new KnowledgeNode("step_1", "Introduce point A", 0),
                new KnowledgeNode("step_2", "Reuse point A", 1));
        ObjectNode firstScene = scenePayload(
                "First scene", List.of(), List.of("A"), Map.of("A", "Point A"));
        ArrayNode constraints = (ArrayNode) firstScene.withArray("new_objects").get(0).get("constraints");
        ObjectNode constraint = constraints.addObject();
        constraint.put("domain", "geometric");
        constraint.put("relation", "lies_on");
        constraint.putObject("refs").put("owner", "A").put("path", "axis_x");
        ObjectNode secondScene = scenePayload(
                "Second scene", List.of("A"), List.of(), Map.of());
        when(aiChatService.requestJson(eq(task), anyList(), anyString()))
                .thenThrow(new IllegalStateException("temporary parse failure"))
                .thenReturn(firstScene, secondScene);

        VisualDesignNode.Result result = node.run(task, bundle, graph, context);

        assertEquals(2, result.getApiCalls());
        ArgumentCaptor<List<AiMessage>> messagesCaptor = messageCaptor();
        verify(aiChatService, times(3)).requestJson(
                eq(task), messagesCaptor.capture(), anyString());
        List<AiMessage> secondSceneMessages = messagesCaptor.getAllValues().get(2);
        assertTrue(messageText(secondSceneMessages.get(2))
                .contains("Previous attempt failed to produce a usable scene"));
        String currentPrompt = messageText(secondSceneMessages.get(secondSceneMessages.size() - 1));
        assertTrue(currentPrompt.contains("constraints="));
        assertTrue(currentPrompt.contains("lies_on"));
        assertTrue(currentPrompt.contains("re-enters the scene"));
    }

    @Test
    void userRevisionRequiresInstructionWithoutCallingAi() {
        KnowledgeGraph graph = graph("step_1", "展示初始图形");
        VisualDesignRequest request = VisualDesignRequest.builder()
                .mode(VisualDesignMode.USER_REVISION)
                .existingNarrative(singleSceneNarrative())
                .instruction(" ")
                .build();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> node.run(task, bundle, graph, request, context));

        assertEquals("User revision instruction cannot be empty.", error.getMessage());
        verify(aiChatService, never()).requestJson(any(), anyList(), anyString());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<List<AiMessage>> messageCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private String messageText(AiMessage message) {
        return message.getParts().isEmpty() ? "" : message.getParts().get(0).getText();
    }

    private KnowledgeGraph graph(String id, String step) {
        return graph(new KnowledgeNode(id, step, 0));
    }

    private KnowledgeGraph graph(KnowledgeNode... nodes) {
        Map<String, KnowledgeNode> nodeMap = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (int i = 0; i < nodes.length; i++) {
            KnowledgeNode knowledgeNode = nodes[i];
            knowledgeNode.setNodeType(i == 0
                    ? KnowledgeNode.NODE_TYPE_PROBLEM
                    : KnowledgeNode.NODE_TYPE_DERIVATION);
            nodeMap.put(knowledgeNode.getId(), knowledgeNode);
            order.add(knowledgeNode.getId());
            if (i + 1 < nodes.length) {
                edges.put(knowledgeNode.getId(), List.of(nodes[i + 1].getId()));
            }
        }
        return new KnowledgeGraph(nodes[0].getId(), nodeMap, edges, order);
    }

    private Narrative existingNarrative() {
        Storyboard storyboard = new Storyboard();
        storyboard.setContinuityPlan("保持对象连续性");
        storyboard.setGlobalVisualRules(List.of("使用统一配色"));
        storyboard.setObjectRegistry(new ArrayList<>(List.of(
                canonicalObject("A", "原始 A"),
                canonicalObject("B", "原始 B"))));
        storyboard.setScenes(new ArrayList<>(List.of(
                scene("scene_1", "原始第一场景", "A", null),
                scene("scene_2", "原始第二场景", "A", "B"))));
        StoryboardCoordinateBounds bounds = new StoryboardCoordinateBounds();
        bounds.setX(new StoryboardCoordinateBoundsAxis(-4.0, 4.0));
        bounds.setY(new StoryboardCoordinateBoundsAxis(-3.0, 3.0));
        storyboard.setCoordinateBounds(bounds);
        return new Narrative("原始目标说明", storyboard);
    }

    private Narrative singleSceneNarrative() {
        Storyboard storyboard = new Storyboard();
        storyboard.setObjectRegistry(new ArrayList<>(List.of(canonicalObject("A", "原始 A"))));
        storyboard.setScenes(new ArrayList<>(List.of(scene("scene_1", "原始场景", "A", null))));
        return new Narrative("原始目标", storyboard);
    }

    private StoryboardScene scene(String sceneId, String title, String persistentId, String enteringId) {
        StoryboardScene scene = new StoryboardScene();
        scene.setSceneId(sceneId);
        scene.setTitle(title);
        scene.setGoal("目标 " + title);
        scene.setNarration("旁白 " + title);
        scene.setLayoutGoal("布局 " + title);
        scene.setPersistentObjects(new ArrayList<>());
        if (persistentId != null) {
            scene.getPersistentObjects().add(objectPatch(persistentId));
        }
        scene.setEnteringObjects(new ArrayList<>());
        if (enteringId != null) {
            scene.getEnteringObjects().add(objectPatch(enteringId));
        }
        StoryboardAction action = new StoryboardAction();
        action.setOrder(1);
        action.setType("highlight");
        action.setTargets(List.of(enteringId != null ? enteringId : persistentId));
        action.setDescription("强调对象");
        scene.setActions(new ArrayList<>(List.of(action)));
        return scene;
    }

    private ObjectNode scenePayload(String title,
                                    List<String> persistentIds,
                                    List<String> enteringIds,
                                    Map<String, String> newObjectContents) {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode scene = payload.putObject("scene");
        scene.put("scene_id", "ignored");
        scene.put("title", title);
        scene.put("goal", "修改后的目标");
        scene.put("narration", "修改后的旁白");
        scene.put("layout_goal", "修改后的布局");
        ArrayNode persistent = scene.putArray("persistent_objects");
        for (String persistentId : persistentIds) {
            persistent.add(objectPatchJson(persistentId));
        }
        ArrayNode entering = scene.putArray("entering_objects");
        for (String enteringId : enteringIds) {
            entering.add(objectPatchJson(enteringId));
        }
        scene.putArray("exiting_objects");
        scene.putArray("actions");

        ArrayNode newObjects = payload.putArray("new_objects");
        newObjectContents.forEach((id, content) ->
                newObjects.add(canonicalObjectJson(id, content)));
        return payload;
    }

    private StoryboardObject registryObject(Narrative narrative, String id) {
        return narrative.getStoryboard().getObjectRegistry().stream()
                .filter(object -> id.equals(object.getId()))
                .findFirst()
                .orElseThrow();
    }

    private StoryboardObject canonicalObject(String id, String content) {
        StoryboardObject object = new StoryboardObject();
        object.setId(id);
        object.setKind("point");
        object.setContent(content);
        return object;
    }

    private StoryboardObject objectPatch(String id) {
        StoryboardObject object = new StoryboardObject();
        object.setId(id);
        return object;
    }

    private ObjectNode objectPatchJson(String id) {
        return objectMapper.createObjectNode().put("id", id);
    }

    private ObjectNode canonicalObjectJson(String id, String content) {
        ObjectNode object = objectMapper.createObjectNode();
        object.put("id", id);
        object.put("kind", "point");
        object.put("content", content);
        object.putArray("constraints");
        return object;
    }
}
