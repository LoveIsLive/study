package com.kwang.study.mathvision.workflow.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionContext;
import com.kwang.study.mathvision.enums.StageEnum;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.workflow.ai.MathVisionAiChatService;
import com.kwang.study.mathvision.workflow.model.AiMessage;
import com.kwang.study.mathvision.workflow.model.CodeResult;
import com.kwang.study.mathvision.workflow.model.Narrative;
import com.kwang.study.mathvision.workflow.model.Narrative.Storyboard;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardConstraint;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardObject;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardScene;
import com.kwang.study.mathvision.workflow.model.ProblemBundle;
import com.kwang.study.mathvision.workflow.model.StageGenerationMode;
import com.kwang.study.mathvision.workflow.model.StageGenerationRequest;
import com.kwang.study.mathvision.workflow.prompt.CodeGenerationPrompts;
import com.kwang.study.mathvision.workflow.prompt.ToolSchemas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodeGenerationNodeTest {

    @Mock private MathVisionAiChatService aiChatService;

    private ObjectMapper objectMapper;
    private CodeGenerationNode node;
    private MathVisionTask task;
    private ProblemBundle bundle;
    private MathVisionStageExecutionContext context;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        node = new CodeGenerationNode(aiChatService, objectMapper);
        task = MathVisionTask.builder().id(103L).outputTarget("manim").build();
        bundle = new ProblemBundle();
        bundle.setId("problem");
        bundle.setTitle("示例题");
        bundle.setStatement("展示两个教学场景");
        bundle.setSceneMode("2d");
        context = MathVisionStageExecutionContext.builder()
                .task(task)
                .stage(StageEnum.CODE_GENERATION)
                .build();
    }

    @Test
    void userRevisionRevisesCompleteCodeInOneCall() throws Exception {
        Narrative narrative = twoSceneNarrative();
        CodeResult existing = new CodeResult();
        existing.setGeneratedCode("class MainScene(Scene):\n    # existing baseline\n    pass");
        StageGenerationRequest<CodeResult> request = StageGenerationRequest.<CodeResult>builder()
                .mode(StageGenerationMode.USER_REVISION)
                .existingArtifact(existing)
                .instruction("放慢第二个场景，并统一重点对象颜色。")
                .baseStageVersion(7)
                .build();
        ObjectNode payload = objectMapper.createObjectNode()
                .put("manimCode", "class MainScene(Scene):\n    # revised complete program\n    pass")
                .put("scene_name", "MainScene")
                .put("description", "revised complete animation");
        when(aiChatService.requestCode(eq(task), anyList(), anyString(), anyList()))
                .thenReturn(codeResponse(payload, payload.path("manimCode").asText()));

        CodeGenerationNode.Result result = node.run(task, bundle, narrative, request, context);

        assertEquals(1, result.getApiCalls());
        assertTrue(result.getCodeResult().getGeneratedCode().contains("revised complete program"));
        assertTrue(result.getCodeResult().getSceneEntries().isEmpty());

        ArgumentCaptor<List<AiMessage>> messagesCaptor = messageCaptor();
        ArgumentCaptor<String> schemaCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<String>> fieldsCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(aiChatService).requestCode(
                eq(task), messagesCaptor.capture(), schemaCaptor.capture(), fieldsCaptor.capture());

        List<AiMessage> messages = messagesCaptor.getValue();
        assertEquals(3, messages.size());
        String revisionRules = messageText(messages.get(0));
        assertTrue(revisionRules.startsWith(CodeGenerationPrompts.buildRulesPrompt("manim", "2d")));
        assertTrue(revisionRules.contains("Complete-code user-revision mode"));
        assertTrue(revisionRules.contains("overrides only the per-scene response granularity"));
        assertTrue(messageText(messages.get(1)).contains("Base code_generation stage version: 7"));
        String userPrompt = messageText(messages.get(2));
        assertTrue(userPrompt.contains("existing baseline"));
        assertTrue(userPrompt.contains("Compact storyboard JSON"));
        assertTrue(userPrompt.contains("scene_1"));
        assertTrue(userPrompt.contains("scene_2"));
        assertTrue(userPrompt.contains("Return the complete executable Python/Manim file"));
        assertEquals(ToolSchemas.MANIM_CODE, schemaCaptor.getValue());
        assertEquals(List.of("manimCode"), fieldsCaptor.getValue());
        assertTrue(messageText(messages.get(2)).contains("放慢第二个场景"));
    }

    @Test
    void initialMultiSceneGenerationStillUsesPerSceneCalls() throws Exception {
        Narrative narrative = twoSceneNarrative();
        when(aiChatService.requestCode(eq(task), anyList(), anyString(), anyList()))
                .thenReturn(codeResponse("pass"), codeResponse("self.wait(2)"));

        CodeGenerationNode.Result result = node.run(task, bundle, narrative, context);

        assertEquals(2, result.getApiCalls());
        assertEquals(2, result.getCodeResult().getSceneEntries().size());
        ArgumentCaptor<String> schemaCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiChatService, times(2)).requestCode(
                eq(task), anyList(), schemaCaptor.capture(), anyList());
        assertEquals(List.of(ToolSchemas.SCENE_CODE, ToolSchemas.SCENE_CODE), schemaCaptor.getAllValues());
    }

    @Test
    void geoGebraUserRevisionUsesCompleteProgramSchemaInOneCall() throws Exception {
        task.setOutputTarget("geogebra");
        Narrative narrative = twoSceneNarrative();
        CodeResult existing = new CodeResult();
        existing.setGeneratedCode("A = (1, 1)");
        StageGenerationRequest<CodeResult> request = StageGenerationRequest.<CodeResult>builder()
                .mode(StageGenerationMode.USER_REVISION)
                .existingArtifact(existing)
                .instruction("Move point A back to the origin.")
                .build();
        ObjectNode payload = objectMapper.createObjectNode()
                .put("geogebraCode", "A = (0, 0)")
                .put("figure_name", "MathVisionFigure");
        when(aiChatService.requestCode(eq(task), anyList(), anyString(), anyList()))
                .thenReturn(codeResponse(payload, payload.path("geogebraCode").asText()));

        CodeGenerationNode.Result result = node.run(task, bundle, narrative, request, context);

        assertEquals(1, result.getApiCalls());
        assertTrue(result.getCodeResult().getGeneratedCode().contains("A = (0, 0)"));
        ArgumentCaptor<String> schemaCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<String>> fieldsCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(aiChatService).requestCode(
                eq(task), anyList(), schemaCaptor.capture(), fieldsCaptor.capture());
        assertEquals(ToolSchemas.GEOGEBRA_CODE, schemaCaptor.getValue());
        assertEquals(List.of("geogebraCode"), fieldsCaptor.getValue());
    }

    @Test
    void singleSceneUsesUpstreamPromptAndToolContract() throws Exception {
        Narrative narrative = new Narrative("single scene target", oneSceneStoryboard());
        ObjectNode payload = objectMapper.createObjectNode()
                .put("manimCode", "class MainScene(Scene):\n    def construct(self):\n        pass")
                .put("scene_name", "MainScene")
                .put("description", "single scene");
        when(aiChatService.requestCode(eq(task), anyList(), anyString(), anyList()))
                .thenReturn(codeResponse(payload, payload.path("manimCode").asText()));

        CodeGenerationNode.Result result = node.run(task, bundle, narrative, context);

        assertEquals("MainScene", result.getCodeResult().getArtifactName());
        ArgumentCaptor<List<AiMessage>> messagesCaptor = messageCaptor();
        ArgumentCaptor<String> schemaCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<String>> fieldsCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(aiChatService).requestCode(
                eq(task), messagesCaptor.capture(), schemaCaptor.capture(), fieldsCaptor.capture());

        List<AiMessage> messages = messagesCaptor.getValue();
        assertEquals(3, messages.size());
        assertTrue(messageText(messages.get(0)).contains("Storyboard style authority rules"));
        assertTrue(messageText(messages.get(1)).contains("Stage 5 / Code Generation"));
        assertTrue(messageText(messages.get(2)).contains("Compact storyboard JSON"));
        assertEquals(ToolSchemas.MANIM_CODE, schemaCaptor.getValue());
        assertEquals(List.of("manimCode"), fieldsCaptor.getValue());
    }

    @Test
    void staticManimSkeletonEnablesVoiceoverWhenStoryboardHasVoiceoverText() {
        Narrative.StoryboardAction action = new Narrative.StoryboardAction();
        action.setVoiceoverText("这里引入关键结论。");
        StoryboardScene scene = scene("scene_1");
        scene.setActions(List.of(action));

        String skeleton = CodeGenerationNode.staticManimSkeleton(
                List.of(scene), List.of("scene_1"), "2d");

        assertTrue(skeleton.contains("from manim_voiceover import VoiceoverScene"));
        assertTrue(skeleton.contains("from manim_voiceover.services.gtts import GTTSService"));
        assertTrue(skeleton.contains("VOICEOVER_SPEED = 1.5"));
        assertTrue(skeleton.contains("class MainScene(VoiceoverScene):"));
        assertTrue(skeleton.contains(
                "self.set_speech_service(GTTSService(lang=\"zh-CN\", global_speed=VOICEOVER_SPEED))"));
    }

    @Test
    void constraintSummaryIncludesRecursivelyReferencedObjectConstraints() throws Exception {
        StoryboardObject a = constrainedObject("A", "point", "A", "B");
        StoryboardObject b = constrainedObject("B", "line", "B", "C");
        StoryboardObject c = constrainedObject("C", "point", "C", "D");
        StoryboardObject d = new StoryboardObject();
        d.setId("D");
        d.setKind("line");

        StoryboardScene scene = scene("scene_1");
        StoryboardObject visiblePatch = new StoryboardObject();
        visiblePatch.setId("A");
        scene.setEnteringObjects(List.of(visiblePatch));
        Map<String, StoryboardObject> registry = new LinkedHashMap<>();
        registry.put("A", a);
        registry.put("B", b);
        registry.put("C", c);
        registry.put("D", d);

        Method method = CodeGenerationNode.class.getDeclaredMethod(
                "buildSceneConstraintSummary", StoryboardScene.class, Map.class);
        method.setAccessible(true);
        String summary = (String) method.invoke(node, scene, registry);

        assertTrue(summary.contains("A(point):"));
        assertTrue(summary.contains("B(line):"));
        assertTrue(summary.contains("C(point):"));
    }

    @Test
    void constraintSummaryPreservesAttachmentOwnerAndDependencyRoles() throws Exception {
        StoryboardConstraint attachment = new StoryboardConstraint();
        attachment.setDomain("attachment");
        attachment.setRelation("label_for");
        attachment.setStrength("hard");
        attachment.setRefs(Map.of("label", "labelP", "anchor", "P"));

        StoryboardObject label = new StoryboardObject();
        label.setId("labelP");
        label.setKind("label");
        label.setConstraints(List.of(attachment));

        StoryboardObject point = new StoryboardObject();
        point.setId("P");
        point.setKind("point");

        StoryboardScene scene = scene("scene_1");
        StoryboardObject visibleLabel = new StoryboardObject();
        visibleLabel.setId("labelP");
        scene.setPersistentObjects(List.of(visibleLabel));

        Map<String, StoryboardObject> registry = new LinkedHashMap<>();
        registry.put("labelP", label);
        registry.put("P", point);

        Method method = CodeGenerationNode.class.getDeclaredMethod(
                "buildSceneConstraintSummary", StoryboardScene.class, Map.class);
        method.setAccessible(true);
        String summary = (String) method.invoke(node, scene, registry);

        assertTrue(summary.contains("owners=[labelP]"));
        assertTrue(summary.contains("dependencies=[P]"));
        assertTrue(summary.contains("owner_roles=[label]"));
        assertTrue(summary.contains("dependency_roles=[anchor]"));
    }

    private Narrative twoSceneNarrative() {
        Storyboard storyboard = new Storyboard();
        storyboard.setObjectRegistry(new ArrayList<>());
        storyboard.setGlobalVisualRules(new ArrayList<>());
        storyboard.setScenes(new ArrayList<>(List.of(scene("scene_1"), scene("scene_2"))));
        return new Narrative("教学目标", storyboard);
    }

    private Storyboard oneSceneStoryboard() {
        Storyboard storyboard = new Storyboard();
        storyboard.setObjectRegistry(new ArrayList<>());
        storyboard.setGlobalVisualRules(new ArrayList<>());
        storyboard.setScenes(new ArrayList<>(List.of(scene("scene_1"))));
        return storyboard;
    }

    private StoryboardScene scene(String id) {
        StoryboardScene scene = new StoryboardScene();
        scene.setSceneId(id);
        scene.setTitle(id);
        scene.setGoal("goal " + id);
        scene.setEnteringObjects(new ArrayList<>());
        scene.setPersistentObjects(new ArrayList<>());
        scene.setExitingObjects(new ArrayList<>());
        scene.setActions(new ArrayList<>());
        return scene;
    }

    private StoryboardObject constrainedObject(String id,
                                               String kind,
                                               String owner,
                                               String dependency) {
        StoryboardConstraint constraint = new StoryboardConstraint();
        constraint.setDomain("geometric");
        constraint.setRelation("lies_on");
        constraint.setRefs(Map.of("owner", owner, "path", dependency));
        StoryboardObject object = new StoryboardObject();
        object.setId(id);
        object.setKind(kind);
        object.setConstraints(List.of(constraint));
        return object;
    }

    private MathVisionAiChatService.CodeResponse codeResponse(String code) throws Exception {
        Constructor<MathVisionAiChatService.CodeResponse> constructor =
                MathVisionAiChatService.CodeResponse.class.getDeclaredConstructor(
                        JsonNode.class, String.class, String.class, String.class, int.class);
        constructor.setAccessible(true);
        ObjectNode payload = objectMapper.createObjectNode().put("sceneCode", code);
        return constructor.newInstance(payload, code, code, "", 1);
    }

    private MathVisionAiChatService.CodeResponse codeResponse(ObjectNode payload, String code) throws Exception {
        Constructor<MathVisionAiChatService.CodeResponse> constructor =
                MathVisionAiChatService.CodeResponse.class.getDeclaredConstructor(
                        JsonNode.class, String.class, String.class, String.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(payload, code, code, "", 1);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<List<AiMessage>> messageCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private String messageText(AiMessage message) {
        return message.getParts().isEmpty() ? "" : message.getParts().get(0).getText();
    }
}
