package com.kwang.study.mathvision.workflow.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionContext;
import com.kwang.study.mathvision.enums.StageEnum;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.workflow.ai.MathVisionAiChatService;
import com.kwang.study.mathvision.workflow.model.AiMessage;
import com.kwang.study.mathvision.workflow.model.ProblemBundle;
import com.kwang.study.mathvision.workflow.model.StageGenerationMode;
import com.kwang.study.mathvision.workflow.model.StageGenerationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProblemNormalizationNodeTest {

    @Mock private MathVisionAiChatService aiChatService;
    @Mock private FileStorageService fileStorageService;

    private ObjectMapper objectMapper;
    private ProblemNormalizationNode node;
    private MathVisionTask task;
    private MathVisionStageExecutionContext context;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        node = new ProblemNormalizationNode(aiChatService, fileStorageService, objectMapper);
        task = MathVisionTask.builder()
                .id(101L)
                .inputText("原始题目：求线段 AB 的长度。")
                .outputTarget("manim")
                .build();
        context = MathVisionStageExecutionContext.builder()
                .task(task)
                .stage(StageEnum.PROBLEM_NORMALIZATION)
                .build();
    }

    @Test
    void userRevisionRegeneratesAndReviewsCompleteProblemBundleWithBaselineContext() {
        ProblemBundle existing = new ProblemBundle();
        existing.setId("old_problem");
        existing.setTitle("原始标题");
        existing.setStatement("旧版规范化题目");
        existing.setInputMode("problem");
        existing.setSceneMode("2d");
        StageGenerationRequest<ProblemBundle> request = StageGenerationRequest.<ProblemBundle>builder()
                .mode(StageGenerationMode.USER_REVISION)
                .existingArtifact(existing)
                .instruction("保持数学含义不变，把题目表述整理得更清晰。")
                .baseStageVersion(3)
                .build();
        when(aiChatService.requestJson(eq(task), anyList(), anyString()))
                .thenReturn(problemBundlePayload("候选版本"), problemBundlePayload("最终修订版本"));

        ProblemNormalizationNode.Result result = node.run(task, request, context);

        assertEquals(2, result.getApiCalls());
        assertEquals("最终修订版本", result.getProblemBundle().getStatement());

        ArgumentCaptor<List<AiMessage>> messagesCaptor = messageCaptor();
        verify(aiChatService, times(2)).requestJson(eq(task), messagesCaptor.capture(), anyString());
        for (List<AiMessage> messages : messagesCaptor.getAllValues()) {
            assertTrue(messageText(messages.get(0)).contains("ProblemBundle user-revision mode"));
            assertTrue(messageText(messages.get(1)).contains("Base problem_normalization stage version: 3"));
            String userPrompt = messageText(messages.get(messages.size() - 1));
            assertTrue(userPrompt.contains("旧版规范化题目"));
            assertTrue(userPrompt.contains("把题目表述整理得更清晰"));
        }
    }

    private ObjectNode problemBundlePayload(String statement) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("id", "problem");
        payload.put("title", "标题");
        payload.put("input_mode", "problem");
        payload.put("scene_mode", "2d");
        payload.put("statement", statement);
        payload.putObject("diagram").put("present", false);
        return payload;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<List<AiMessage>> messageCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private String messageText(AiMessage message) {
        return message.getParts().isEmpty() ? "" : message.getParts().get(0).getText();
    }
}
