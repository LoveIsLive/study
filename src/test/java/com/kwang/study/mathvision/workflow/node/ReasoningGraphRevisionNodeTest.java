package com.kwang.study.mathvision.workflow.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionContext;
import com.kwang.study.mathvision.enums.StageEnum;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.workflow.ai.MathVisionAiChatService;
import com.kwang.study.mathvision.workflow.model.AiMessage;
import com.kwang.study.mathvision.workflow.model.KnowledgeGraph;
import com.kwang.study.mathvision.workflow.model.KnowledgeNode;
import com.kwang.study.mathvision.workflow.model.ProblemBundle;
import com.kwang.study.mathvision.workflow.model.StageGenerationMode;
import com.kwang.study.mathvision.workflow.model.StageGenerationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReasoningGraphRevisionNodeTest {

    @Mock private MathVisionAiChatService aiChatService;

    private ObjectMapper objectMapper;
    private MathVisionTask task;
    private ProblemBundle bundle;
    private MathVisionStageExecutionContext context;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        task = MathVisionTask.builder().id(102L).outputTarget("manim").build();
        bundle = new ProblemBundle();
        bundle.setId("problem");
        bundle.setTitle("示例题");
        bundle.setStatement("证明两个角相等");
        bundle.setInputMode("problem");
        bundle.setSceneMode("2d");
        context = MathVisionStageExecutionContext.builder()
                .task(task)
                .stage(StageEnum.REASONING_GRAPH)
                .build();
    }

    @Test
    void explorationRevisionRegeneratesCompleteGraphWithBaselineContext() {
        KnowledgeGraph existing = graph("旧版观察", "旧版结论");
        StageGenerationRequest<KnowledgeGraph> request = revisionRequest(existing);
        when(aiChatService.requestJson(eq(task), anyList(), anyString()))
                .thenReturn(graphPayload("新版观察", "新版结论"));

        ExplorationNode.Result result = new ExplorationNode(aiChatService, objectMapper)
                .run(task, bundle, request, context);

        assertEquals(2, result.getGraph().countNodes());
        assertEquals("新版观察", result.getGraph().getNode("step_1").getStep());
        ArgumentCaptor<List<AiMessage>> messagesCaptor = messageCaptor();
        verify(aiChatService).requestJson(eq(task), messagesCaptor.capture(), anyString());
        List<AiMessage> messages = messagesCaptor.getValue();
        assertTrue(messageText(messages.get(0)).contains("Reasoning-graph user-revision mode"));
        assertTrue(messageText(messages.get(0)).contains("each node's `step` and `reason`"));
        assertTrue(messageText(messages.get(0)).contains("do not translate it to English or pinyin"));
        assertTrue(messageText(messages.get(1)).contains("Base reasoning_graph stage version: 5"));
        assertTrue(messageText(messages.get(2)).contains("旧版观察"));
        assertTrue(messageText(messages.get(2)).contains("将辅助构造提前"));
    }

    @Test
    void explorationAcceptsPlainTextClassifierDecision() {
        bundle.setInputMode(null);
        when(aiChatService.requestText(eq(task), anyList(), anyString(), anyList()))
                .thenReturn("concept");
        when(aiChatService.requestJson(eq(task), anyList(), anyString()))
                .thenReturn(graphPayload("Introduce the concept", "Summarize the concept"));

        ExplorationNode.Result result = new ExplorationNode(aiChatService, objectMapper)
                .run(task, bundle, context);

        assertEquals("concept", result.getResolvedInputMode());
        assertEquals(2, result.getApiCalls());
        verify(aiChatService).requestText(eq(task), anyList(), anyString(), eq(List.of("input_mode")));
    }

    @Test
    void enrichmentRevisionRegeneratesEveryNodeWithItsBaselineContent() {
        KnowledgeGraph existing = graph("旧版观察", "旧版结论");
        existing.getNode("step_1").setInterpretation("旧版直观解释一");
        existing.getNode("step_2").setInterpretation("旧版直观解释二");
        KnowledgeGraph regenerated = graph("新版观察", "新版结论");
        StageGenerationRequest<KnowledgeGraph> request = revisionRequest(existing);
        reset(aiChatService);
        when(aiChatService.requestJson(eq(task), anyList(), anyString()))
                .thenReturn(enrichmentPayload("补充一"), enrichmentPayload("补充二"));

        MathEnrichmentNode.Result result = new MathEnrichmentNode(aiChatService, objectMapper)
                .run(task, bundle, regenerated, request, context);

        assertEquals(2, result.getApiCalls());
        assertEquals(2, result.getEnrichedCount());
        ArgumentCaptor<List<AiMessage>> messagesCaptor = messageCaptor();
        verify(aiChatService, times(2)).requestJson(eq(task), messagesCaptor.capture(), anyString());
        List<List<AiMessage>> calls = messagesCaptor.getAllValues();
        assertTrue(lastMessageText(calls.get(0)).contains("旧版直观解释一"));
        assertTrue(lastMessageText(calls.get(1)).contains("旧版直观解释二"));
        for (List<AiMessage> messages : calls) {
            assertTrue(messageText(messages.get(0)).contains("Mathematical-enrichment user-revision mode"));
            assertTrue(messageText(messages.get(0)).contains("every `definitions` value"));
            assertTrue(messageText(messages.get(0)).contains("natural-Chinese reason text"));
            assertTrue(lastMessageText(messages).contains("将辅助构造提前"));
        }
    }

    @Test
    void explorationUsesChineseTextForSyntheticRootFallback() {
        task.setOutputTarget("geogebra");
        ObjectNode payload = graphPayload("观察变量变化", "得到最终结论");
        payload.putObject("next_edges")
                .putArray("step_1").add("step_2");
        ((ObjectNode) payload.get("next_edges"))
                .putArray("step_2").add("step_1");
        when(aiChatService.requestJson(eq(task), anyList(), anyString())).thenReturn(payload);

        ExplorationNode.Result result = new ExplorationNode(aiChatService, objectMapper)
                .run(task, bundle, context);

        KnowledgeNode syntheticStart = result.getGraph().getStartNode();
        assertEquals("呈现问题并引出第一步解题思路", syntheticStart.getStep());
        assertEquals("该步骤用于明确问题情境，并建立后续求解的起点。", syntheticStart.getReason());
    }

    private StageGenerationRequest<KnowledgeGraph> revisionRequest(KnowledgeGraph existing) {
        return StageGenerationRequest.<KnowledgeGraph>builder()
                .mode(StageGenerationMode.USER_REVISION)
                .existingArtifact(existing)
                .instruction("将辅助构造提前，并保留其他正确内容。")
                .baseStageVersion(5)
                .build();
    }

    private KnowledgeGraph graph(String firstStep, String secondStep) {
        KnowledgeNode first = new KnowledgeNode("step_1", firstStep, 0);
        first.setNodeType(KnowledgeNode.NODE_TYPE_PROBLEM);
        KnowledgeNode second = new KnowledgeNode("step_2", secondStep, 1);
        second.setNodeType(KnowledgeNode.NODE_TYPE_CONCLUSION);
        Map<String, KnowledgeNode> nodes = new LinkedHashMap<>();
        nodes.put(first.getId(), first);
        nodes.put(second.getId(), second);
        return new KnowledgeGraph(first.getId(), nodes, Map.of(first.getId(), List.of(second.getId())),
                List.of(first.getId(), second.getId()));
    }

    private ObjectNode graphPayload(String firstStep, String secondStep) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("start_id", "step_1");
        ArrayNode nodes = payload.putArray("nodes");
        nodes.add(graphNode("step_1", firstStep, "problem", 0));
        nodes.add(graphNode("step_2", secondStep, "conclusion", 1));
        payload.putObject("next_edges").putArray("step_1").add("step_2");
        payload.putArray("teaching_order").add("step_1").add("step_2");
        return payload;
    }

    private ObjectNode graphNode(String id, String step, String type, int depth) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", id);
        node.put("step", step);
        node.put("reason", "reason " + id);
        node.put("node_type", type);
        node.put("min_depth", depth);
        return node;
    }

    private ObjectNode enrichmentPayload(String interpretation) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("step", "保持步骤");
        payload.put("reason", "保持原因");
        payload.putArray("equations").add("a=b");
        payload.putObject("definitions").put("a", "线段长度");
        payload.put("interpretation", interpretation);
        payload.putArray("examples");
        return payload;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<List<AiMessage>> messageCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private String lastMessageText(List<AiMessage> messages) {
        return messageText(messages.get(messages.size() - 1));
    }

    private String messageText(AiMessage message) {
        return message.getParts().isEmpty() ? "" : message.getParts().get(0).getText();
    }
}
