package com.kwang.study.mathvision.workflow.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.config.MathVisionModelCatalog.ModelCatalog;
import com.kwang.study.mathvision.config.MathVisionModelCatalog.ProviderCatalog;
import com.kwang.study.mathvision.mapper.LlmModelConfigMapper;
import com.kwang.study.mathvision.pojo.LlmModelConfig;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.util.ApiKeyCipher;
import com.kwang.study.mathvision.workflow.model.AiContentPart;
import com.kwang.study.mathvision.workflow.model.AiMessage;
import com.kwang.study.mathvision.workflow.prompt.ToolSchemas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MathVisionAiChatServiceTest {

    private LlmModelConfigMapper configMapper;
    private HttpClient httpClient;
    private MathVisionAiChatService service;
    private MathVisionTask task;
    private MathVisionModelCatalog catalog;
    private ProviderCatalog catalogProvider;
    private ModelCatalog catalogModel;

    @BeforeEach
    void setUp() {
        configMapper = mock(LlmModelConfigMapper.class);
        httpClient = mock(HttpClient.class);

        ApiKeyCipher cipher = new ApiKeyCipher("mathvision-ai-test-secret");
        LlmModelConfig config = LlmModelConfig.builder()
                .id(9L)
                .ownerUserId(1L)
                .provider("zhipu")
                .apiKeyEncrypted(cipher.encrypt("test-api-key"))
                .status("enabled")
                .temperature(0.9D)
                .topP(0.95D)
                .build();
        when(configMapper.findById(9L)).thenReturn(config);

        catalogModel = new ModelCatalog();
        catalogModel.setModelName("GLM-5V-Turbo");
        catalogModel.setContextWindow(200_000);
        catalogModel.setMaxOutputTokens(128_000);
        catalogModel.setTemperature(0.4D);
        catalogModel.setTopP(0.7D);
        catalogModel.setThinking("disabled");
        catalogModel.setTransientFailureRetries(0);
        catalogModel.setRateLimitRetries(0);

        catalogProvider = new ProviderCatalog();
        catalogProvider.setCode("zhipu");
        catalogProvider.setName("Zhipu");
        catalogProvider.setBaseUrl("https://example.invalid/v1");
        catalogProvider.setEnabled(true);
        catalogProvider.setReasoningContentFallback(true);
        catalogProvider.setModels(List.of(catalogModel));

        catalog = new MathVisionModelCatalog();
        catalog.setModelProviders(List.of(catalogProvider));
        service = new MathVisionAiChatService(configMapper, catalog, cipher, new ObjectMapper(), httpClient);

        task = new MathVisionTask();
        task.setId(2L);
        task.setUserId(1L);
        task.setProviderCode("zhipu");
        task.setModelName("GLM-5V-Turbo");
        task.setSelectedModelConfigId(9L);
    }

    @Test
    void usesReasoningContentOnPlainTextFallback() throws Exception {
        enqueueResponses(
                emptyOpenAiResponse(),
                reasoningOpenAiResponse(fixedCode()));

        MathVisionAiChatService.CodeResponse response = requestCode();

        assertTrue(response.hasCode());
        assertTrue(response.getCode().contains("ArrowTriangleFilledTip"));
        assertEquals(2, response.getApiCalls());
        verifySends(2);
    }

    @Test
    void retriesSemanticallyEmptyPlainTextResponses() throws Exception {
        enqueueResponses(
                emptyOpenAiResponse(),
                emptyOpenAiResponse(),
                emptyOpenAiResponse(),
                contentOpenAiResponse(fixedCode()));

        MathVisionAiChatService.CodeResponse response = requestCode();

        assertTrue(response.hasCode());
        assertTrue(response.getCode().contains("ArrowTriangleFilledTip"));
        assertEquals(2, response.getApiCalls());
        verifySends(4);
    }

    @Test
    void combinesToolAndPlainTextFailuresWithoutDuplicatingTheFirstFailure() throws Exception {
        enqueueResponses(
                emptyOpenAiResponse(),
                emptyOpenAiResponse(),
                emptyOpenAiResponse(),
                emptyOpenAiResponse());

        MathVisionAiChatService.CodeResponse response = requestCode();

        assertTrue(!response.hasCode());
        assertTrue(response.getFailureReason().startsWith("Tool response extraction failed:"));
        assertTrue(response.getFailureReason().contains("plain-text retry failed:"));
        assertEquals(2, occurrences(response.getFailureReason(), "No tool-call payload"));
        verifySends(4);
    }

    @Test
    void retriesJsonRequestWithoutToolsWhenToolPayloadIsUnusable() throws Exception {
        enqueueResponses(
                plainContentOpenAiResponse("not-json"),
                plainContentOpenAiResponse("{\"input_mode\":\"problem\"}"));

        JsonNode payload = service.requestJson(
                task,
                List.of(AiMessage.user(List.of(AiContentPart.text("Classify the input.")))),
                ToolSchemas.INPUT_MODE);

        assertEquals("problem", payload.path("input_mode").asText());
        verifySends(2);
    }

    @Test
    void acceptsPlainTextForToolBackedTextClassification() throws Exception {
        enqueueResponses(plainContentOpenAiResponse("concept\n"));

        String result = service.requestText(
                task,
                List.of(AiMessage.user(List.of(AiContentPart.text("Classify the input.")))),
                ToolSchemas.INPUT_MODE,
                List.of("input_mode"));

        assertEquals("concept", result);
        verifySends(1);
    }

    @Test
    void doesNotAcceptCodeFromFieldsOutsideTheDeclaredToolContract() throws Exception {
        enqueueResponses(
                toolCodeResponse("code", fixedCode()),
                emptyOpenAiResponse(),
                emptyOpenAiResponse(),
                emptyOpenAiResponse());

        MathVisionAiChatService.CodeResponse response = requestCode();

        assertTrue(!response.hasCode());
        assertTrue(response.getFailureReason().contains("manimCode"));
        verifySends(4);
    }

    @Test
    void acceptsPlainTextSceneMethodWithoutGenericCodeHeuristics() throws Exception {
        String sceneMethod = String.join("\n",
                "def scene_1(self):",
                "    title = MathTex(r\"x^2\")",
                "    self.play(Write(title))");
        enqueueResponses(
                emptyOpenAiResponse(),
                plainContentOpenAiResponse(sceneMethod));

        MathVisionAiChatService.CodeResponse response = service.requestCode(
                task,
                List.of(AiMessage.user(List.of(AiContentPart.text("Generate one scene method.")))),
                ToolSchemas.SCENE_CODE,
                List.of("sceneCode"));

        assertTrue(response.hasCode());
        assertEquals(sceneMethod, response.getCode());
        assertEquals(2, response.getApiCalls());
        verifySends(2);
    }

    @Test
    void usesOnlyNacosModelRuntimeOptions() throws Exception {
        enqueueResponses(plainContentOpenAiResponse("ok"));

        service.requestRawResponse(
                task,
                List.of(AiMessage.user(List.of(AiContentPart.text("hello")))),
                null);

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(
                requestCaptor.capture(),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        JsonNode body = new ObjectMapper().readTree(readRequestBody(requestCaptor.getValue()));
        assertEquals(0.4D, body.path("temperature").asDouble());
        assertEquals(0.7D, body.path("top_p").asDouble());
        assertEquals("disabled", body.path("thinking").path("type").asText());
    }

    @Test
    void inheritsProviderAndGlobalNacosRuntimeDefaults() throws Exception {
        catalogModel.setTemperature(null);
        catalogModel.setTopP(null);
        catalogModel.setThinking(null);
        catalogProvider.setTemperature(0.55D);
        catalogProvider.setThinking("disabled");
        catalog.getModelDefaults().setTopP(0.65D);
        enqueueResponses(plainContentOpenAiResponse("ok"));

        service.requestRawResponse(
                task,
                List.of(AiMessage.user(List.of(AiContentPart.text("hello")))),
                null);

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(
                requestCaptor.capture(),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        JsonNode body = new ObjectMapper().readTree(readRequestBody(requestCaptor.getValue()));
        assertEquals(0.55D, body.path("temperature").asDouble());
        assertEquals(0.65D, body.path("top_p").asDouble());
        assertEquals("disabled", body.path("thinking").path("type").asText());
    }

    @Test
    void keepsFullContextBudgetWhenCatalogOutputEqualsContextWindow() throws Exception {
        catalogModel.setContextWindow(262_144);
        catalogModel.setMaxOutputTokens(262_144);
        var method = MathVisionAiChatService.class.getDeclaredMethod(
                "resolvePromptInputBudgetTokens", ModelCatalog.class);
        method.setAccessible(true);

        assertEquals(262_144, method.invoke(service, catalogModel));
    }

    @Test
    void honorsNacosEmptyResponseRetryBudget() throws Exception {
        catalog.getModelDefaults().setEmptyResponseRetries(0);
        enqueueResponses(emptyOpenAiResponse());

        service.requestRawResponse(
                task,
                List.of(AiMessage.user(List.of(AiContentPart.text("hello")))),
                null);

        verifySends(1);
    }

    @Test
    void preservesToolPayloadInAssistantTranscriptForFixConversations() throws Exception {
        enqueueResponses(toolCodeResponse("manimCode", fixedCode()));

        MathVisionAiChatService.CodeResponse response = requestCode();

        assertTrue(response.hasCode());
        assertTrue(response.getAssistantText().contains("Tool payload:"));
        assertTrue(response.getAssistantText().contains("manimCode"));
        verifySends(1);
    }

    private MathVisionAiChatService.CodeResponse requestCode() {
        return service.requestCode(
                task,
                List.of(AiMessage.user(List.of(AiContentPart.text("Fix the Manim runtime error.")))),
                ToolSchemas.MANIM_CODE,
                List.of("manimCode"));
    }

    @SafeVarargs
    private final void enqueueResponses(HttpResponse<String>... responses) throws Exception {
        when(httpClient.send(
                any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(responses[0], tail(responses));
    }

    private void verifySends(int count) throws Exception {
        verify(httpClient, times(count)).send(
                any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    private String readRequestBody(HttpRequest request) throws Exception {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CompletableFuture<Void> completed = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.write(bytes, 0, bytes.length);
            }

            @Override
            public void onError(Throwable throwable) {
                completed.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                completed.complete(null);
            }
        });
        completed.get();
        return output.toString(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String>[] tail(HttpResponse<String>[] responses) {
        HttpResponse<String>[] tail = new HttpResponse[Math.max(0, responses.length - 1)];
        if (tail.length > 0) {
            System.arraycopy(responses, 1, tail, 0, tail.length);
        }
        return tail;
    }

    private HttpResponse<String> emptyOpenAiResponse() {
        return response("{\"choices\":[{\"message\":{\"content\":null},\"finish_reason\":\"stop\"}]}");
    }

    private HttpResponse<String> reasoningOpenAiResponse(String code) throws Exception {
        return response(openAiResponse("reasoning_content", fenced(code)));
    }

    private HttpResponse<String> contentOpenAiResponse(String code) throws Exception {
        return response(openAiResponse("content", fenced(code)));
    }

    private HttpResponse<String> plainContentOpenAiResponse(String content) throws Exception {
        return response(openAiResponse("content", content));
    }

    private HttpResponse<String> toolCodeResponse(String field, String code) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode message = root.putArray("choices").addObject().putObject("message");
        message.putNull("content");
        ObjectNode function = message.putArray("tool_calls").addObject()
                .put("id", "call_1")
                .put("type", "function")
                .putObject("function");
        function.put("name", "write_manim_code");
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put(field, code);
        function.put("arguments", mapper.writeValueAsString(arguments));
        return response(mapper.writeValueAsString(root));
    }

    private String openAiResponse(String field, String value) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject();
        choice.putObject("message").put(field, value);
        choice.put("finish_reason", "stop");
        return mapper.writeValueAsString(root);
    }

    private HttpResponse<String> response(String body) {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        return response;
    }

    private String fenced(String code) {
        return "```python\n" + code + "\n```";
    }

    private String fixedCode() {
        return String.join("\n",
                "from manim import *",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        tip = ArrowTriangleFilledTip(color=RED)");
    }

    private int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while (text != null && (index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
