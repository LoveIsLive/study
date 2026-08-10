package com.kwang.study.mathvision.workflow.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import com.kwang.study.mathvision.workflow.util.JsonUtils;
import com.kwang.study.mathvision.workflow.util.NodeConversationContext;
import com.kwang.study.mathvision.workflow.util.NodeExecutionLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpHeaders;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MathVisionAiChatService {

    private static final Logger log = LoggerFactory.getLogger(MathVisionAiChatService.class);
    private static final int DEFAULT_EMPTY_RESPONSE_RETRIES = 2;
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 300;
    private static final int DEFAULT_TIMEOUT_RETRY_ATTEMPTS = 1;
    private static final double DEFAULT_TIMEOUT_RETRY_MULTIPLIER = 2.0D;
    private static final int DEFAULT_MAX_REQUEST_TIMEOUT_SECONDS = 900;
    private static final int DEFAULT_TRANSIENT_FAILURE_RETRIES = 2;
    private static final int DEFAULT_RATE_LIMIT_RETRIES = 12;
    private static final long DEFAULT_RATE_LIMIT_BASE_DELAY_MILLIS = 5_000L;
    private static final long DEFAULT_RATE_LIMIT_MAX_DELAY_MILLIS = 300_000L;
    private static final long TRANSIENT_RETRY_BASE_DELAY_MILLIS = 1_000L;
    private static final long TRANSIENT_RETRY_MAX_DELAY_MILLIS = 4_000L;
    private static final double RATE_LIMIT_JITTER_RATIO = 0.25D;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;
    private static final int DEFAULT_MAX_INPUT_TOKENS = 131_072;
    private static final int MIN_PROMPT_INPUT_BUDGET_TOKENS = 4_096;
    private static final Pattern FENCED_CODE_BLOCK = Pattern.compile(
            "(?is)```+\\s*([A-Za-z0-9_+-]*)?\\s*\\R?([\\s\\S]*?)\\R?```+"
    );

    private final LlmModelConfigMapper configMapper;
    private final MathVisionModelCatalog catalog;
    private final ApiKeyCipher cipher;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    @Autowired
    public MathVisionAiChatService(LlmModelConfigMapper configMapper,
                                   MathVisionModelCatalog catalog,
                                   ApiKeyCipher cipher,
                                   ObjectMapper mapper) {
        this(configMapper, catalog, cipher, mapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .version(HttpClient.Version.HTTP_1_1)
                .build());
    }

    MathVisionAiChatService(LlmModelConfigMapper configMapper,
                            MathVisionModelCatalog catalog,
                            ApiKeyCipher cipher,
                            ObjectMapper mapper,
                            HttpClient httpClient) {
        this.configMapper = configMapper;
        this.catalog = catalog;
        this.cipher = cipher;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    public JsonNode requestJson(MathVisionTask task, List<AiMessage> messages, String toolsJson) {
        NodeExecutionLogger.recordLogicalRequest();
        JsonNode response = requestRaw(task, messages, toolsJson);
        JsonExtraction first = extractJsonResponse(response);
        if (first.payload != null || !StringUtils.hasText(toolsJson)) {
            if (first.payload != null) {
                return first.payload;
            }
            throw new IllegalStateException(first.failureReason);
        }

        log.debug("Tool response did not contain usable JSON; retrying without tools: {}",
                first.failureReason);
        JsonNode plainResponse = requestRaw(task, messages, null);
        JsonExtraction fallback = extractJsonResponse(plainResponse);
        if (fallback.payload != null) {
            return fallback.payload;
        }
        throw new IllegalStateException(combineFailureReasons(first.failureReason, fallback.failureReason));
    }

    public String requestText(MathVisionTask task,
                              List<AiMessage> messages,
                              String toolsJson,
                              List<String> preferredPayloadFields) {
        NodeExecutionLogger.recordLogicalRequest();
        List<String> fields = normalizePreferredFields(preferredPayloadFields);
        JsonNode response = requestRaw(task, messages, toolsJson);
        TextExtraction first = extractPreferredText(response, fields);
        if (StringUtils.hasText(first.text) || !StringUtils.hasText(toolsJson)) {
            if (StringUtils.hasText(first.text)) {
                return first.text.trim();
            }
            throw new IllegalStateException(first.failureReason);
        }

        JsonNode plainResponse = requestRaw(task, messages, null);
        TextExtraction fallback = extractPreferredText(plainResponse, fields);
        if (StringUtils.hasText(fallback.text)) {
            return fallback.text.trim();
        }
        throw new IllegalStateException(combineFailureReasons(first.failureReason, fallback.failureReason));
    }

    private TextExtraction extractPreferredText(JsonNode response, List<String> preferredPayloadFields) {
        List<String> failureReasons = new ArrayList<>();
        JsonNode payload = extractToolPayload(response);
        String payloadText = extractPreferredTextField(payload, preferredPayloadFields);
        if (StringUtils.hasText(payloadText)) {
            return TextExtraction.success(payloadText);
        }
        failureReasons.add(payload == null || payload.isNull()
                ? "No tool-call payload"
                : "Tool-call payload did not contain preferred text fields " + preferredPayloadFields);

        String assistantText = extractAssistantText(response);
        if (!StringUtils.hasText(assistantText)) {
            failureReasons.add("message.content was empty");
            return TextExtraction.failure(combineFailureReasons(failureReasons));
        }
        JsonNode textPayload = JsonUtils.parseTreeBestEffort(assistantText);
        String parsedText = extractPreferredTextField(textPayload, preferredPayloadFields);
        return TextExtraction.success(StringUtils.hasText(parsedText) ? parsedText : assistantText.trim());
    }

    private String extractPreferredTextField(JsonNode payload, List<String> preferredPayloadFields) {
        if (payload == null || payload.isNull()) {
            return "";
        }
        if (payload.isTextual()) {
            return payload.asText("");
        }
        if (!payload.isObject()) {
            return "";
        }
        for (String field : preferredPayloadFields) {
            JsonNode value = payload.get(field);
            if (value != null && value.isValueNode() && !value.isNull()) {
                String text = value.asText("");
                if (StringUtils.hasText(text)) {
                    return text;
                }
            }
        }
        return "";
    }

    /**
     * Raw provider response used by the shared math-vision core adapter.
     * Provider selection, credentials, timeout and retry behavior remain owned by
     * the platform, while response parsing remains owned by the core workflow.
     */
    public JsonNode requestRawResponse(MathVisionTask task, List<AiMessage> messages, String toolsJson) {
        NodeExecutionLogger.recordLogicalRequest();
        return requestRaw(task, messages, toolsJson);
    }

    public JsonNode extractToolPayloadForCore(JsonNode response) {
        return extractToolPayload(response);
    }

    public String extractAssistantTextForCore(JsonNode response) {
        return extractAssistantText(response);
    }

    public CodeResponse requestCode(MathVisionTask task,
                                    List<AiMessage> messages,
                                    String toolsJson,
                                    List<String> preferredPayloadFields) {
        NodeExecutionLogger.recordLogicalRequest();
        List<String> fields = normalizePreferredFields(preferredPayloadFields);
        JsonNode response = requestRaw(task, messages, toolsJson);
        CodeResponse first = extractCodeResponse(response, fields, 1);
        if (first.hasCode() || !StringUtils.hasText(toolsJson)) {
            return first;
        }

        JsonNode plainResponse = requestRaw(task, messages, null);
        CodeResponse fallback = extractCodeResponse(plainResponse, fields, 2);
        return fallback.hasCode() ? fallback : first.withCombinedFailure(fallback);
    }

    private JsonNode requestRaw(MathVisionTask task, List<AiMessage> messages, String toolsJson) {
        AiRuntime runtime = resolveRuntime(task);
        List<AiMessage> preparedMessages = NodeConversationContext.trimAiMessagesToFitBudget(
                messages,
                runtime.promptInputBudgetTokens,
                toolsJson);
        String provider = runtime.protocolCode.toLowerCase(Locale.ROOT);
        if ("google".equals(provider)) {
            return requestGemini(runtime, preparedMessages, toolsJson);
        }
        if ("anthropic".equals(provider)) {
            return requestAnthropic(runtime, preparedMessages, toolsJson);
        }
        return requestOpenAiCompatible(runtime, preparedMessages, toolsJson);
    }

    private CodeResponse extractCodeResponse(JsonNode response,
                                             List<String> preferredPayloadFields,
                                             int apiCalls) {
        JsonNode payload = extractToolPayload(response);
        String assistantText = extractAssistantText(response);
        String assistantTranscript = buildAssistantTranscript(assistantText, payload);
        JsonNode resultPayload = payload;
        List<String> failureReasons = new ArrayList<>();

        String code = extractPreferredPayloadCode(payload, preferredPayloadFields, failureReasons);
        if (StringUtils.hasText(code)) {
            return new CodeResponse(payload, code, assistantTranscript, "", apiCalls);
        }

        JsonNode textPayload = JsonUtils.parseTreeBestEffort(assistantText);
        if (isUsableObject(textPayload)) {
            code = extractPreferredPayloadCode(textPayload, preferredPayloadFields, failureReasons);
            if (StringUtils.hasText(code)) {
                return new CodeResponse(textPayload, code, assistantTranscript, "", apiCalls);
            }
            if (resultPayload == null || resultPayload.isNull()) {
                resultPayload = textPayload;
            }
        } else if (!StringUtils.hasText(assistantText)) {
            failureReasons.add("message.content was empty");
        } else {
            failureReasons.add("message.content did not contain parseable JSON payload");
        }

        code = JsonUtils.extractCodeBlock(assistantText);
        if (StringUtils.hasText(code)) {
            return new CodeResponse(resultPayload, code, assistantTranscript, "", apiCalls);
        }

        failureReasons.add("message.content did not contain usable code");
        return new CodeResponse(resultPayload, "", assistantTranscript, combineFailureReasons(failureReasons), apiCalls);
    }

    private String buildAssistantTranscript(String assistantText, JsonNode toolPayload) {
        boolean hasText = StringUtils.hasText(assistantText);
        boolean hasPayload = toolPayload != null && !toolPayload.isNull();
        if (!hasPayload) {
            return hasText ? assistantText : "";
        }
        String payloadText;
        try {
            payloadText = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(toolPayload);
        } catch (Exception e) {
            payloadText = toolPayload.toString();
        }
        return hasText
                ? assistantText.trim() + "\n\nTool payload:\n" + payloadText
                : "Tool payload:\n" + payloadText;
    }

    private JsonExtraction extractJsonResponse(JsonNode response) {
        List<String> failureReasons = new ArrayList<>();
        JsonNode payload = extractToolPayload(response);
        if (isUsableObject(payload)) {
            return JsonExtraction.success(payload);
        }
        if (payload == null || payload.isNull()) {
            failureReasons.add("No tool-call payload");
        } else {
            failureReasons.add("Tool-call payload was rejected because it was not a usable JSON object");
        }

        String text = extractAssistantText(response);
        if (!StringUtils.hasText(text)) {
            failureReasons.add("message.content was empty");
        } else {
            JsonUtils.JsonObjectExtractionResult extraction =
                    JsonUtils.extractJsonObjectResult("message.content", text);
            JsonNode fromText = extraction.getPayload();
            if (isUsableObject(fromText)) {
                return JsonExtraction.success(fromText);
            }
            failureReasons.add(StringUtils.hasText(extraction.getFailureReason())
                    ? extraction.getFailureReason()
                    : fromText != null
                    ? "Parsed JSON payload was rejected because it was not a usable JSON object"
                    : "No parseable JSON object found in message content");
        }
        return JsonExtraction.failure(combineFailureReasons(failureReasons));
    }

    private List<String> normalizePreferredFields(List<String> preferredPayloadFields) {
        List<String> fields = new ArrayList<>();
        if (preferredPayloadFields != null) {
            for (String field : preferredPayloadFields) {
                addPreferredField(fields, field);
            }
        }
        return fields;
    }

    private void addPreferredField(List<String> fields, String field) {
        if (!StringUtils.hasText(field) || fields.contains(field)) {
            return;
        }
        fields.add(field);
    }

    private String extractPreferredPayloadCode(JsonNode payload,
                                               List<String> preferredPayloadFields,
                                               List<String> failureReasons) {
        if (payload == null || payload.isNull()) {
            failureReasons.add("No tool-call payload");
            return "";
        }
        if (preferredPayloadFields == null || preferredPayloadFields.isEmpty()) {
            failureReasons.add("No preferred payload fields were configured");
            return "";
        }

        List<String> missingFields = new ArrayList<>();
        List<String> rejectedFields = new ArrayList<>();
        for (String field : preferredPayloadFields) {
            JsonNode value = payload.get(field);
            if (value == null || value.isNull()) {
                missingFields.add(field);
                continue;
            }
            String raw = value.isTextual() ? value.asText("") : value.toString();
            String extracted = extractFencedCodeBlock(raw);
            if (!StringUtils.hasText(extracted)) {
                extracted = extractRawCodeLikeText(raw);
            }
            if (!StringUtils.hasText(extracted) && StringUtils.hasText(raw)) {
                extracted = raw.trim();
            }
            if (StringUtils.hasText(extracted)) {
                return extracted;
            }
            rejectedFields.add(field);
        }
        failureReasons.add(buildPayloadFieldFailureReason(preferredPayloadFields, missingFields, rejectedFields));
        return "";
    }

    private String buildPayloadFieldFailureReason(List<String> preferredPayloadFields,
                                                  List<String> missingFields,
                                                  List<String> rejectedFields) {
        StringBuilder sb = new StringBuilder("Tool-call payload did not contain usable code fields");
        if (preferredPayloadFields != null && !preferredPayloadFields.isEmpty()) {
            sb.append("; preferred=").append(preferredPayloadFields);
        }
        if (missingFields != null && !missingFields.isEmpty()) {
            sb.append("; missing=").append(missingFields);
        }
        if (rejectedFields != null && !rejectedFields.isEmpty()) {
            sb.append("; rejected=").append(rejectedFields);
        }
        return sb.toString();
    }

    private String extractFencedCodeBlock(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        Matcher matcher = FENCED_CODE_BLOCK.matcher(text.trim());
        String bestBlock = "";
        int bestScore = -1;
        while (matcher.find()) {
            String language = matcher.group(1);
            String block = stripMarkdownCodeFences(matcher.group(2));
            int score = scoreCodeBlock(language, block);
            if (score > bestScore) {
                bestScore = score;
                bestBlock = block;
            }
        }
        return StringUtils.hasText(bestBlock) ? bestBlock.trim() : "";
    }

    private String extractRawCodeLikeText(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String stripped = stripMarkdownCodeFences(text).trim();
        if (looksLikeCode(stripped)) {
            return stripped;
        }
        return "";
    }

    private String stripMarkdownCodeFences(String text) {
        if (text == null) {
            return "";
        }
        String stripped = text.trim();
        if (stripped.startsWith("```")) {
            int lineBreak = stripped.indexOf('\n');
            if (lineBreak >= 0) {
                stripped = stripped.substring(lineBreak + 1);
            }
        }
        while (stripped.endsWith("`")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped.trim();
    }

    private int scoreCodeBlock(String language, String block) {
        if (!StringUtils.hasText(block)) {
            return 0;
        }
        int score = looksLikeCode(block) ? 10 : 1;
        String normalizedLanguage = language == null ? "" : language.toLowerCase(Locale.ROOT);
        if (normalizedLanguage.contains("python")
                || normalizedLanguage.equals("py")
                || normalizedLanguage.contains("geogebra")
                || normalizedLanguage.equals("ggb")) {
            score += 5;
        }
        if (block.contains("class MainScene") || block.contains("def construct")) {
            score += 5;
        }
        return score;
    }

    private boolean looksLikeCode(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String normalized = text.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        return normalized.contains("from manim import")
                || normalized.contains("class MainScene")
                || normalized.matches("(?s).*class\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\([^)]*Scene[^)]*\\)\\s*:.*")
                || normalized.contains("def construct(")
                || lower.contains("geogebra")
                || normalized.matches("(?m)^\\s*[A-Za-z][A-Za-z0-9_]*\\s*=\\s*\\([^\\n]+\\)\\s*$.*")
                || normalized.matches("(?m)^\\s*(Point|Segment|Line|Circle|Arc|Polygon|Intersect|Angle|Text|SetColor|ShowLabel)\\s*\\(.*");
    }

    private String combineFailureReasons(List<String> failureReasons) {
        if (failureReasons == null || failureReasons.isEmpty()) {
            return "";
        }
        List<String> unique = new ArrayList<>();
        for (String reason : failureReasons) {
            if (StringUtils.hasText(reason) && !unique.contains(reason.trim())) {
                unique.add(reason.trim());
            }
        }
        return String.join("; ", unique);
    }

    private String combineFailureReasons(String firstReason, String fallbackReason) {
        boolean hasFirst = StringUtils.hasText(firstReason);
        boolean hasFallback = StringUtils.hasText(fallbackReason);
        if (hasFirst && hasFallback) {
            return "Tool response extraction failed: " + firstReason
                    + "; plain-text retry failed: " + fallbackReason;
        }
        if (hasFirst) {
            return "Tool response extraction failed: " + firstReason;
        }
        return hasFallback
                ? "plain-text retry failed: " + fallbackReason
                : "AI response did not contain usable output";
    }

    private AiRuntime resolveRuntime(MathVisionTask task) {
        if (task == null) {
            throw new IllegalArgumentException("MathVision task is missing.");
        }
        LlmModelConfig config = task.getSelectedModelConfigId() != null
                ? configMapper.findById(task.getSelectedModelConfigId())
                : configMapper.findByOwnerAndProvider(task.getUserId(), task.getProviderCode());
        if (config == null || !task.getUserId().equals(config.getOwnerUserId())) {
            throw new IllegalArgumentException("No model credential was found for the current user.");
        }
        if (!task.getProviderCode().equalsIgnoreCase(config.getProvider())) {
            throw new IllegalArgumentException("Task provider does not match credential provider.");
        }
        if (!"enabled".equalsIgnoreCase(config.getStatus())) {
            throw new IllegalArgumentException("Model credential is not enabled.");
        }
        String apiKey = cipher.decrypt(config.getApiKeyEncrypted());
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("Model API key is empty.");
        }
        if (Boolean.TRUE.equals(config.getIsCustom())) {
            return resolveCustomRuntime(task, config, apiKey);
        }

        ProviderCatalog provider = catalog.findEnabled(task.getProviderCode());
        if (provider == null) {
            throw new IllegalArgumentException("Model provider is not enabled: " + task.getProviderCode());
        }
        ModelCatalog model = catalog.findModel(task.getProviderCode(), task.getModelName());
        if (model == null) {
            throw new IllegalArgumentException("Model is not configured in Nacos math-vision: "
                    + task.getProviderCode() + "/" + task.getModelName());
        }
        validateNacosModelRuntime(provider.getCode(), model);
        return new AiRuntime(
                task.getId(),
                provider.getCode(),
                provider.getCode(),
                resolveBaseUrl(provider.getCode(), provider.getBaseUrl()),
                task.getModelName(),
                apiKey,
                resolveTemperature(provider, model),
                firstConfigured(model.getTopP(), provider.getTopP(), catalog.getModelDefaults().getTopP()),
                model.getMaxOutputTokens(),
                resolvePromptInputBudgetTokens(model),
                firstNonBlank(model.getExtraHeadersJson(), provider.getExtraHeadersJson()),
                resolveRequestTimeoutSeconds(provider, model),
                resolveTimeoutRetryAttempts(provider, model),
                resolveTimeoutRetryMultiplier(provider, model),
                resolveMaxRequestTimeoutSeconds(provider, model),
                resolveEmptyResponseRetries(),
                resolveTransientFailureRetries(provider, model),
                resolveTransientRetryBaseDelayMillis(),
                resolveTransientRetryMaxDelayMillis(),
                resolveRateLimitRetries(provider, model),
                resolveRateLimitBaseDelayMillis(provider, model),
                resolveRateLimitMaxDelayMillis(provider, model),
                resolveRateLimitJitterRatio(),
                resolveReasoningContentFallback(provider, model),
                Boolean.TRUE.equals(firstConfigured(
                        model.getAdaptiveThinking(), provider.getAdaptiveThinking(), false)),
                firstNonBlank(model.getEffort(), provider.getEffort()),
                firstNonBlank(model.getThinking(), provider.getThinking())
        );
    }

    private AiRuntime resolveCustomRuntime(MathVisionTask task,
                                           LlmModelConfig config,
                                           String apiKey) {
        String protocol = config.getCompatibilityType() == null
                ? ""
                : config.getCompatibilityType().trim().toLowerCase(Locale.ROOT);
        if (!"openai".equals(protocol) && !"anthropic".equals(protocol) && !"google".equals(protocol)) {
            throw new IllegalArgumentException("Unsupported custom provider compatibility type: " + protocol);
        }
        if (!StringUtils.hasText(config.getBaseUrl())) {
            throw new IllegalArgumentException("Custom model provider baseUrl is missing.");
        }
        if (config.getContextWindow() == null || config.getContextWindow() <= 0
                || config.getMaxOutputTokens() == null || config.getMaxOutputTokens() <= 0) {
            throw new IllegalArgumentException("Custom model context settings are incomplete: "
                    + config.getModelName());
        }

        ModelCatalog customModel = new ModelCatalog();
        customModel.setModelName(config.getModelName());
        customModel.setContextWindow(config.getContextWindow());
        customModel.setMaxOutputTokens(config.getMaxOutputTokens());
        customModel.setTemperature(config.getTemperature());
        customModel.setTopP(config.getTopP());
        Double temperature = firstConfigured(
                config.getTemperature(), catalog.getModelDefaults().getTemperature(), 0.6D);

        return new AiRuntime(
                task.getId(),
                config.getProvider(),
                protocol,
                trimTrailingSlash(config.getBaseUrl()),
                task.getModelName(),
                apiKey,
                temperature,
                firstConfigured(config.getTopP(), catalog.getModelDefaults().getTopP()),
                config.getMaxOutputTokens(),
                resolvePromptInputBudgetTokens(customModel),
                config.getExtraHeadersJson(),
                resolveRequestTimeoutSeconds(null, null),
                resolveTimeoutRetryAttempts(null, null),
                resolveTimeoutRetryMultiplier(null, null),
                resolveMaxRequestTimeoutSeconds(null, null),
                resolveEmptyResponseRetries(),
                resolveTransientFailureRetries(null, null),
                resolveTransientRetryBaseDelayMillis(),
                resolveTransientRetryMaxDelayMillis(),
                resolveRateLimitRetries(null, null),
                resolveRateLimitBaseDelayMillis(null, null),
                resolveRateLimitMaxDelayMillis(null, null),
                resolveRateLimitJitterRatio(),
                "openai".equals(protocol) && resolveReasoningContentFallback(null, null),
                false,
                "",
                ""
        );
    }

    private void validateNacosModelRuntime(String providerCode, ModelCatalog model) {
        if (model.getContextWindow() == null || model.getContextWindow() <= 0) {
            throw new IllegalArgumentException("Nacos math-vision model is missing contextWindow: "
                    + model.getModelName());
        }
        if (model.getMaxOutputTokens() == null || model.getMaxOutputTokens() <= 0) {
            throw new IllegalArgumentException("Nacos math-vision model is missing maxOutputTokens: "
                    + model.getModelName());
        }
        if (!"anthropic".equalsIgnoreCase(providerCode)
                && resolveTemperature(catalog.findEnabled(providerCode), model) == null) {
            throw new IllegalArgumentException("Nacos math-vision model is missing temperature: "
                    + model.getModelName());
        }
    }

    private Double resolveTemperature(ProviderCatalog provider, ModelCatalog model) {
        return firstConfigured(
                model != null ? model.getTemperature() : null,
                provider != null ? provider.getTemperature() : null,
                catalog.getModelDefaults() != null ? catalog.getModelDefaults().getTemperature() : null);
    }

    private JsonNode requestOpenAiCompatible(AiRuntime runtime,
                                             List<AiMessage> messages,
                                             String toolsJson) {
        String url = trimTrailingSlash(runtime.baseUrl) + "/chat/completions";
        try {
            return sendOpenAiCompatibleWithSemanticRetry(
                    runtime,
                    url,
                    buildOpenAiCompatibleBody(runtime, messages, toolsJson, true),
                    StringUtils.hasText(toolsJson),
                    0);
        } catch (AiHttpException e) {
            if (e.getStatusCode() != 400 || !StringUtils.hasText(toolsJson)) {
                throw e;
            }
            log.warn("MathVision AI provider rejected tool calling, retrying without tools, provider={}, model={}",
                    runtime.providerCode, runtime.model);
            return sendOpenAiCompatibleWithSemanticRetry(
                    runtime,
                    url,
                    buildOpenAiCompatibleBody(runtime, messages, toolsJson, false),
                    false,
                    0);
        }
    }

    private JsonNode sendOpenAiCompatibleWithSemanticRetry(AiRuntime runtime,
                                                            String url,
                                                            ObjectNode body,
                                                            boolean allowToolOnlyResponse,
                                                            int emptyAttempt) {
        JsonNode response = postJson(runtime, url, body, builder -> {
            builder.header("Authorization", "Bearer " + runtime.apiKey);
            applyExtraHeaders(builder, runtime.extraHeadersJson);
        });
        if (allowToolOnlyResponse || hasOpenAiAssistantOutput(response)) {
            return response;
        }

        JsonNode reasoningFallback = applyReasoningContentFallback(runtime, response);
        if (hasOpenAiAssistantOutput(reasoningFallback)) {
            return reasoningFallback;
        }

        if (emptyAttempt < runtime.emptyResponseRetries) {
            log.warn("MathVision AI returned empty content, retrying, provider={}, model={}, attempt={}/{}",
                    runtime.providerCode,
                    runtime.model,
                    emptyAttempt + 1,
                    runtime.emptyResponseRetries + 1);
            return sendOpenAiCompatibleWithSemanticRetry(
                    runtime, url, body, false, emptyAttempt + 1);
        }

        log.warn("MathVision AI returned empty content after all attempts, provider={}, model={}, attempts={}",
                runtime.providerCode, runtime.model, runtime.emptyResponseRetries + 1);
        return response;
    }

    private boolean hasOpenAiAssistantOutput(JsonNode response) {
        JsonNode message = response.path("choices").path(0).path("message");
        if (StringUtils.hasText(extractText(message.get("content")))) {
            return true;
        }
        JsonNode toolCalls = message.path("tool_calls");
        return toolCalls.isArray() && !toolCalls.isEmpty();
    }

    private JsonNode applyReasoningContentFallback(AiRuntime runtime, JsonNode response) {
        if (!runtime.reasoningContentFallback || response == null || response.isNull()) {
            return response;
        }
        JsonNode message = response.path("choices").path(0).path("message");
        String reasoningContent = extractText(message.get("reasoning_content"));
        if (!StringUtils.hasText(reasoningContent)) {
            return response;
        }

        JsonNode copied = response.deepCopy();
        JsonNode copiedMessage = copied.path("choices").path(0).path("message");
        if (copiedMessage instanceof ObjectNode) {
            ((ObjectNode) copiedMessage).put("content", reasoningContent);
            log.debug("MathVision AI using reasoning_content as fallback, provider={}, model={}, chars={}",
                    runtime.providerCode, runtime.model, reasoningContent.length());
            return copied;
        }
        return response;
    }

    private ObjectNode buildOpenAiCompatibleBody(AiRuntime runtime,
                                                 List<AiMessage> messages,
                                                 String toolsJson,
                                                 boolean includeTools) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", runtime.model);
        body.put("temperature", runtime.temperature);
        body.put("max_tokens", runtime.maxOutputTokens);
        if (runtime.topP != null) {
            body.put("top_p", runtime.topP);
        }
        addOpenAiThinking(body, runtime);
        body.set("messages", openAiMessages(messages, isZhipu(runtime.providerCode)));
        if (includeTools) {
            addOpenAiTools(body, toolsJson, runtime);
        }
        return body;
    }

    private JsonNode requestGemini(AiRuntime runtime,
                                   List<AiMessage> messages,
                                   String toolsJson) {
        ObjectNode body = mapper.createObjectNode();
        String system = collectSystemText(messages);
        if (StringUtils.hasText(system)) {
            ObjectNode instruction = body.putObject("system_instruction");
            instruction.putArray("parts").addObject().put("text", system);
        }
        ArrayNode contents = body.putArray("contents");
        for (AiMessage message : messages) {
            if ("system".equals(message.getRole())) {
                continue;
            }
            ObjectNode entry = contents.addObject();
            entry.put("role", "assistant".equals(message.getRole()) ? "model" : "user");
            ArrayNode parts = entry.putArray("parts");
            for (AiContentPart part : safeParts(message)) {
                if ("image".equals(part.getType())) {
                    ObjectNode inlineData = parts.addObject().putObject("inline_data");
                    inlineData.put("mime_type", part.getMimeType());
                    inlineData.put("data", part.getDataBase64());
                } else {
                    parts.addObject().put("text", part.getText() != null ? part.getText() : "");
                }
            }
        }
        ObjectNode generationConfig = body.putObject("generationConfig");
        generationConfig.put("temperature", runtime.temperature);
        generationConfig.put("maxOutputTokens", runtime.maxOutputTokens);
        addGeminiTools(body, toolsJson);

        String url = geminiGenerateUrl(runtime.baseUrl, runtime.model, runtime.apiKey);
        return postJson(runtime, url, body, builder -> applyExtraHeaders(builder, runtime.extraHeadersJson));
    }

    private JsonNode requestAnthropic(AiRuntime runtime,
                                      List<AiMessage> messages,
                                      String toolsJson) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", runtime.model);
        body.put("max_tokens", runtime.maxOutputTokens);
        if (runtime.adaptiveThinking) {
            body.putObject("thinking").put("type", "adaptive");
        }
        if (StringUtils.hasText(runtime.effort)) {
            body.putObject("output_config").put("effort", runtime.effort.trim().toLowerCase(Locale.ROOT));
        }
        String system = collectSystemText(messages);
        if (StringUtils.hasText(system)) {
            body.put("system", system);
        }
        ArrayNode messageArray = body.putArray("messages");
        for (AiMessage message : messages) {
            if ("system".equals(message.getRole())) {
                continue;
            }
            ObjectNode entry = messageArray.addObject();
            entry.put("role", "assistant".equals(message.getRole()) ? "assistant" : "user");
            ArrayNode content = entry.putArray("content");
            for (AiContentPart part : safeParts(message)) {
                if ("image".equals(part.getType())) {
                    ObjectNode source = content.addObject()
                            .put("type", "image")
                            .putObject("source");
                    source.put("type", "base64");
                    source.put("media_type", part.getMimeType());
                    source.put("data", part.getDataBase64());
                } else {
                    content.addObject()
                            .put("type", "text")
                            .put("text", part.getText() != null ? part.getText() : "");
                }
            }
        }
        addAnthropicTools(body, toolsJson);

        String url = anthropicMessagesUrl(runtime.baseUrl);
        return postJson(runtime, url, body, builder -> {
            builder.header("x-api-key", runtime.apiKey);
            builder.header("anthropic-version", "2023-06-01");
            applyExtraHeaders(builder, runtime.extraHeadersJson);
        });
    }

    private JsonNode postJson(AiRuntime runtime,
                              String url,
                              ObjectNode body,
                              HeaderCustomizer customizer) {
        String jsonBody;
        try {
            jsonBody = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize AI request body: " + e.getMessage(), e);
        }
        return postJsonWithRetry(runtime, url, jsonBody, customizer, 0, 0, 0,
                initialTimeoutSeconds(runtime));
    }

    private JsonNode postJsonWithRetry(AiRuntime runtime,
                                       String url,
                                       String jsonBody,
                                       HeaderCustomizer customizer,
                                       int transientAttempt,
                                       int rateLimitAttempt,
                                       int timeoutAttempt,
                                       int timeoutSeconds) {
        int requestAttempt = transientAttempt + rateLimitAttempt + timeoutAttempt + 1;
        try {
            if (aiTraceEnabled()) {
                MathVisionAiTraceLogger.logRequest(
                        runtime.taskId, runtime.providerCode, runtime.model, url,
                        requestAttempt, timeoutSeconds, jsonBody, aiTraceMaxChars());
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json");
            customizer.apply(builder);
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
            NodeExecutionLogger.recordHttpAttempt();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (aiTraceEnabled()) {
                MathVisionAiTraceLogger.logResponse(
                        runtime.taskId, runtime.providerCode, runtime.model,
                        requestAttempt, response.statusCode(), response.body(), aiTraceMaxChars());
            }
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode parsed = mapper.readTree(response.body());
                logSuccessfulResponse(runtime, response.statusCode(), parsed);
                return parsed;
            }
            if (isRateLimitStatusCode(response.statusCode())
                    && rateLimitAttempt < rateLimitRetries(runtime)) {
                sleep(rateLimitDelayMillis(runtime, rateLimitAttempt, retryAfterHeader(response.headers())));
                return postJsonWithRetry(runtime, url, jsonBody, customizer,
                        transientAttempt, rateLimitAttempt + 1, timeoutAttempt, timeoutSeconds);
            }
            if (isRetryableStatusCode(response.statusCode())
                    && transientAttempt < transientFailureRetries(runtime)) {
                sleep(transientRetryDelayMillis(runtime, transientAttempt));
                return postJsonWithRetry(runtime, url, jsonBody, customizer,
                        transientAttempt + 1, rateLimitAttempt, timeoutAttempt, timeoutSeconds);
            }
            throw new AiHttpException(response.statusCode(), response.body());
        } catch (AiHttpException e) {
            if (aiTraceEnabled()) {
                MathVisionAiTraceLogger.logFailure(
                        runtime.taskId, runtime.providerCode, runtime.model, requestAttempt, e);
            }
            throw e;
        } catch (Exception e) {
            if (aiTraceEnabled()) {
                MathVisionAiTraceLogger.logFailure(
                        runtime.taskId, runtime.providerCode, runtime.model, requestAttempt, e);
            }
            if (isTimeoutFailure(e) && timeoutAttempt < timeoutRetryAttempts(runtime)) {
                int nextTimeoutSeconds = nextTimeoutSeconds(runtime, timeoutSeconds);
                return postJsonWithRetry(runtime, url, jsonBody, customizer,
                        transientAttempt, rateLimitAttempt, timeoutAttempt + 1, nextTimeoutSeconds);
            }
            if (isRateLimitFailure(e) && rateLimitAttempt < rateLimitRetries(runtime)) {
                sleep(rateLimitDelayMillis(runtime, rateLimitAttempt, Optional.empty()));
                return postJsonWithRetry(runtime, url, jsonBody, customizer,
                        transientAttempt, rateLimitAttempt + 1, timeoutAttempt, timeoutSeconds);
            }
            if (isRetryableTransportFailure(e) && transientAttempt < transientFailureRetries(runtime)) {
                sleep(transientRetryDelayMillis(runtime, transientAttempt));
                return postJsonWithRetry(runtime, url, jsonBody, customizer,
                        transientAttempt + 1, rateLimitAttempt, timeoutAttempt, timeoutSeconds);
            }
            throw new IllegalStateException("AI API call failed: " + e.getMessage(), e);
        }
    }

    private ArrayNode openAiMessages(List<AiMessage> messages, boolean rawBase64ImageUrl) {
        ArrayNode array = mapper.createArrayNode();
        for (AiMessage message : messages) {
            ObjectNode node = array.addObject();
            node.put("role", message.getRole());
            List<AiContentPart> parts = safeParts(message);
            if (isTextOnly(parts)) {
                node.put("content", textContent(parts));
                continue;
            }
            ArrayNode content = mapper.createArrayNode();
            for (AiContentPart part : parts) {
                if ("image".equals(part.getType())) {
                    ObjectNode image = content.addObject();
                    image.put("type", "image_url");
                    String imageUrl = rawBase64ImageUrl
                            ? part.getDataBase64()
                            : "data:" + part.getMimeType() + ";base64," + part.getDataBase64();
                    image.putObject("image_url")
                            .put("url", imageUrl);
                } else {
                    content.addObject()
                            .put("type", "text")
                            .put("text", part.getText() != null ? part.getText() : "");
                }
            }
            node.set("content", content);
        }
        return array;
    }

    private void addOpenAiThinking(ObjectNode body, AiRuntime runtime) {
        if (runtime == null || !StringUtils.hasText(runtime.thinking)) {
            return;
        }
        body.putObject("thinking").put("type", runtime.thinking.trim().toLowerCase(Locale.ROOT));
    }

    private void addOpenAiTools(ObjectNode body, String toolsJson, AiRuntime runtime) {
        JsonNode tools = parseTools(toolsJson);
        if (tools == null || !tools.isArray() || tools.isEmpty()) {
            return;
        }
        body.set("tools", tools);
        if (tools.size() == 1 && canForceSingleToolChoice(runtime)) {
            String name = tools.get(0).path("function").path("name").asText("");
            if (StringUtils.hasText(name)) {
                ObjectNode toolChoice = body.putObject("tool_choice");
                toolChoice.put("type", "function");
                toolChoice.putObject("function").put("name", name);
            }
        }
    }

    private void addGeminiTools(ObjectNode body, String toolsJson) {
        JsonNode tools = parseTools(toolsJson);
        if (tools == null || !tools.isArray() || tools.isEmpty()) {
            return;
        }
        ArrayNode declarations = mapper.createArrayNode();
        for (JsonNode tool : tools) {
            JsonNode function = tool.path("function");
            String name = function.path("name").asText("");
            if (!StringUtils.hasText(name)) {
                continue;
            }
            ObjectNode declaration = declarations.addObject();
            declaration.put("name", name);
            declaration.put("description", function.path("description").asText("Return structured output for " + name + "."));
            declaration.set("parametersJsonSchema", function.path("parameters"));
        }
        if (declarations.isEmpty()) {
            return;
        }
        ArrayNode geminiTools = body.putArray("tools");
        geminiTools.addObject().set("functionDeclarations", declarations);
        ObjectNode config = body.putObject("toolConfig").putObject("functionCallingConfig");
        config.put("mode", declarations.size() == 1 ? "ANY" : "AUTO");
        if (declarations.size() == 1) {
            config.putArray("allowedFunctionNames").add(declarations.get(0).path("name").asText());
        }
    }

    private void addAnthropicTools(ObjectNode body, String toolsJson) {
        JsonNode tools = parseTools(toolsJson);
        if (tools == null || !tools.isArray() || tools.isEmpty()) {
            return;
        }
        ArrayNode anthropicTools = body.putArray("tools");
        for (JsonNode tool : tools) {
            JsonNode function = tool.path("function");
            String name = function.path("name").asText("");
            if (!StringUtils.hasText(name)) {
                continue;
            }
            ObjectNode item = anthropicTools.addObject();
            item.put("name", name);
            String description = function.path("description").asText("");
            if (StringUtils.hasText(description)) {
                item.put("description", description);
            }
            item.set("input_schema", function.path("parameters"));
        }
        if (anthropicTools.size() == 1) {
            body.putObject("tool_choice")
                    .put("type", "tool")
                    .put("name", anthropicTools.get(0).path("name").asText());
        }
    }

    private JsonNode extractToolPayload(JsonNode root) {
        JsonNode openAiCalls = root.path("choices").path(0).path("message").path("tool_calls");
        if (openAiCalls.isArray() && !openAiCalls.isEmpty()) {
            return parseArguments(openAiCalls.get(0).path("function").get("arguments"));
        }
        JsonNode geminiParts = root.path("candidates").path(0).path("content").path("parts");
        if (geminiParts.isArray()) {
            for (JsonNode part : geminiParts) {
                JsonNode functionCall = firstPresent(part, "functionCall", "function_call");
                if (functionCall != null && !functionCall.isMissingNode() && !functionCall.isNull()) {
                    return parseArguments(firstPresent(functionCall, "args", "arguments"));
                }
            }
        }
        JsonNode anthropicContent = root.path("content");
        if (anthropicContent.isArray()) {
            for (JsonNode block : anthropicContent) {
                if ("tool_use".equals(block.path("type").asText(""))) {
                    return parseArguments(block.get("input"));
                }
            }
        }
        return null;
    }

    private String extractAssistantText(JsonNode root) {
        String openAi = extractText(root.path("choices").path(0).path("message").get("content"));
        if (StringUtils.hasText(openAi)) {
            return openAi;
        }
        JsonNode geminiParts = root.path("candidates").path(0).path("content").path("parts");
        if (geminiParts.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : geminiParts) {
                String text = part.path("text").asText("");
                if (StringUtils.hasText(text)) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(text.trim());
                }
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        }
        JsonNode anthropicContent = root.path("content");
        if (anthropicContent.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : anthropicContent) {
                if ("text".equals(block.path("type").asText(""))) {
                    String text = block.path("text").asText("");
                    if (StringUtils.hasText(text)) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(text.trim());
                    }
                }
            }
            return sb.toString();
        }
        return "";
    }

    private JsonNode parseArguments(JsonNode arguments) {
        if (arguments == null || arguments.isNull() || arguments.isMissingNode()) {
            return null;
        }
        if (arguments.isObject() || arguments.isArray()) {
            return arguments;
        }
        return JsonUtils.parseTreeBestEffort(arguments.asText(""));
    }

    private String extractText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : node) {
                String value = extractText(firstPresent(item, "text", "content"));
                if (StringUtils.hasText(value)) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(value.trim());
                }
            }
            return sb.toString();
        }
        if (node.isObject()) {
            return extractText(firstPresent(node, "text", "content"));
        }
        return "";
    }

    private JsonNode parseTools(String toolsJson) {
        if (!StringUtils.hasText(toolsJson)) {
            return null;
        }
        try {
            return mapper.readTree(toolsJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Tool schema is not valid JSON", e);
        }
    }

    private int resolveRequestTimeoutSeconds(ProviderCatalog provider, ModelCatalog model) {
        return positiveOrDefault(firstConfigured(
                        model != null ? model.getRequestTimeoutSeconds() : null,
                        provider != null ? provider.getRequestTimeoutSeconds() : null,
                        catalog.getModelDefaults().getRequestTimeoutSeconds()),
                DEFAULT_REQUEST_TIMEOUT_SECONDS);
    }

    private int resolvePromptInputBudgetTokens(ModelCatalog model) {
        int maxOutputTokens = positiveOrDefault(model != null ? model.getMaxOutputTokens() : null,
                DEFAULT_MAX_OUTPUT_TOKENS);
        Integer contextWindow = model != null ? model.getContextWindow() : null;
        if (contextWindow == null || contextWindow <= 0) {
            return DEFAULT_MAX_INPUT_TOKENS;
        }
        if (contextWindow <= maxOutputTokens) {
            return contextWindow;
        }
        return Math.max(MIN_PROMPT_INPUT_BUDGET_TOKENS, contextWindow - maxOutputTokens);
    }

    private int resolveTimeoutRetryAttempts(ProviderCatalog provider, ModelCatalog model) {
        return nonNegativeOrDefault(firstConfigured(
                        model != null ? model.getTimeoutRetryAttempts() : null,
                        provider != null ? provider.getTimeoutRetryAttempts() : null,
                        catalog.getModelDefaults().getTimeoutRetryAttempts()),
                DEFAULT_TIMEOUT_RETRY_ATTEMPTS);
    }

    private double resolveTimeoutRetryMultiplier(ProviderCatalog provider, ModelCatalog model) {
        Double configured = firstConfigured(
                model != null ? model.getTimeoutRetryMultiplier() : null,
                provider != null ? provider.getTimeoutRetryMultiplier() : null,
                catalog.getModelDefaults().getTimeoutRetryMultiplier());
        return configured != null && configured > 1.0D ? configured : DEFAULT_TIMEOUT_RETRY_MULTIPLIER;
    }

    private int resolveMaxRequestTimeoutSeconds(ProviderCatalog provider, ModelCatalog model) {
        return positiveOrDefault(firstConfigured(
                        model != null ? model.getMaxRequestTimeoutSeconds() : null,
                        provider != null ? provider.getMaxRequestTimeoutSeconds() : null,
                        catalog.getModelDefaults().getMaxRequestTimeoutSeconds()),
                DEFAULT_MAX_REQUEST_TIMEOUT_SECONDS);
    }

    private int resolveTransientFailureRetries(ProviderCatalog provider, ModelCatalog model) {
        return nonNegativeOrDefault(firstConfigured(
                        model != null ? model.getTransientFailureRetries() : null,
                        provider != null ? provider.getTransientFailureRetries() : null,
                        catalog.getModelDefaults().getTransientFailureRetries()),
                DEFAULT_TRANSIENT_FAILURE_RETRIES);
    }

    private int resolveEmptyResponseRetries() {
        return nonNegativeOrDefault(
                catalog.getModelDefaults().getEmptyResponseRetries(),
                DEFAULT_EMPTY_RESPONSE_RETRIES);
    }

    private long resolveTransientRetryBaseDelayMillis() {
        return positiveOrDefault(
                catalog.getModelDefaults().getTransientRetryBaseDelayMillis(),
                TRANSIENT_RETRY_BASE_DELAY_MILLIS);
    }

    private long resolveTransientRetryMaxDelayMillis() {
        long base = resolveTransientRetryBaseDelayMillis();
        Long configured = catalog.getModelDefaults().getTransientRetryMaxDelayMillis();
        return configured != null && configured >= base
                ? configured
                : Math.max(TRANSIENT_RETRY_MAX_DELAY_MILLIS, base);
    }

    private int resolveRateLimitRetries(ProviderCatalog provider, ModelCatalog model) {
        return nonNegativeOrDefault(firstConfigured(
                        model != null ? model.getRateLimitRetries() : null,
                        provider != null ? provider.getRateLimitRetries() : null,
                        catalog.getModelDefaults().getRateLimitRetries()),
                DEFAULT_RATE_LIMIT_RETRIES);
    }

    private long resolveRateLimitBaseDelayMillis(ProviderCatalog provider, ModelCatalog model) {
        return positiveOrDefault(firstConfigured(
                        model != null ? model.getRateLimitBaseDelayMillis() : null,
                        provider != null ? provider.getRateLimitBaseDelayMillis() : null,
                        catalog.getModelDefaults().getRateLimitBaseDelayMillis()),
                DEFAULT_RATE_LIMIT_BASE_DELAY_MILLIS);
    }

    private long resolveRateLimitMaxDelayMillis(ProviderCatalog provider, ModelCatalog model) {
        long base = resolveRateLimitBaseDelayMillis(provider, model);
        Long configured = firstConfigured(
                model != null ? model.getRateLimitMaxDelayMillis() : null,
                provider != null ? provider.getRateLimitMaxDelayMillis() : null,
                catalog.getModelDefaults().getRateLimitMaxDelayMillis());
        return configured != null && configured >= base ? configured : DEFAULT_RATE_LIMIT_MAX_DELAY_MILLIS;
    }

    private double resolveRateLimitJitterRatio() {
        Double configured = catalog.getModelDefaults().getRateLimitJitterRatio();
        if (configured == null || configured < 0.0D) {
            return RATE_LIMIT_JITTER_RATIO;
        }
        return Math.min(configured, 1.0D);
    }

    private boolean resolveReasoningContentFallback(ProviderCatalog provider, ModelCatalog model) {
        return Boolean.TRUE.equals(firstConfigured(
                model != null ? model.getReasoningContentFallback() : null,
                provider != null ? provider.getReasoningContentFallback() : null,
                catalog.getModelDefaults().getReasoningContentFallback()));
    }

    @SafeVarargs
    private final <T> T firstConfigured(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private int initialTimeoutSeconds(AiRuntime runtime) {
        return Math.max(runtime.requestTimeoutSeconds, 1);
    }

    private int timeoutRetryAttempts(AiRuntime runtime) {
        return Math.max(runtime.timeoutRetryAttempts, 0);
    }

    private int transientFailureRetries(AiRuntime runtime) {
        return Math.max(runtime.transientFailureRetries, 0);
    }

    private int rateLimitRetries(AiRuntime runtime) {
        return Math.max(runtime.rateLimitRetries, 0);
    }

    private int nextTimeoutSeconds(AiRuntime runtime, int currentTimeoutSeconds) {
        long next = Math.max(currentTimeoutSeconds + 1L,
                Math.round(currentTimeoutSeconds * runtime.timeoutRetryMultiplier));
        int max = Math.max(runtime.maxRequestTimeoutSeconds, initialTimeoutSeconds(runtime));
        return (int) Math.min(next, max);
    }

    private long transientRetryDelayMillis(AiRuntime runtime, int attempt) {
        long delay = multiplyByPowerOfTwoSaturated(
                Math.max(runtime.transientRetryBaseDelayMillis, 1L),
                Math.max(attempt, 0));
        return Math.min(delay, Math.max(runtime.transientRetryMaxDelayMillis, 1L));
    }

    private long rateLimitDelayMillis(AiRuntime runtime, int attempt, Optional<String> retryAfterHeader) {
        Optional<Long> retryAfterMillis = parseRetryAfterMillis(retryAfterHeader);
        if (retryAfterMillis.isPresent()) {
            return clampRateLimitDelay(runtime, retryAfterMillis.get());
        }

        long baseDelay = Math.max(runtime.rateLimitBaseDelayMillis, 1L);
        long maxDelay = Math.max(runtime.rateLimitMaxDelayMillis, baseDelay);
        long exponential = multiplyByPowerOfTwoSaturated(baseDelay, Math.max(attempt, 0));
        long capped = Math.min(exponential, maxDelay);
        long jitterWindow = Math.max(0L, Math.round(capped * runtime.rateLimitJitterRatio));
        long jitter = jitterWindow > 0L
                ? ThreadLocalRandom.current().nextLong(Math.min(jitterWindow, Integer.MAX_VALUE) + 1L)
                : 0L;
        return Math.min(capped + jitter, maxDelay);
    }

    private Optional<String> retryAfterHeader(HttpHeaders headers) {
        if (headers == null) {
            return Optional.empty();
        }
        return headers.firstValue("Retry-After").or(() -> headers.firstValue("retry-after"));
    }

    private Optional<Long> parseRetryAfterMillis(Optional<String> retryAfterHeader) {
        if (retryAfterHeader == null || retryAfterHeader.isEmpty()) {
            return Optional.empty();
        }
        String value = retryAfterHeader.get();
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            return Optional.of(Math.max(seconds, 0L) * 1_000L);
        } catch (NumberFormatException ignored) {
        }
        try {
            ZonedDateTime retryAt = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
            long millis = Duration.between(ZonedDateTime.now(retryAt.getZone()), retryAt).toMillis();
            return Optional.of(Math.max(millis, 0L));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private long clampRateLimitDelay(AiRuntime runtime, long delayMillis) {
        long baseDelay = Math.max(runtime.rateLimitBaseDelayMillis, 1L);
        long maxDelay = Math.max(runtime.rateLimitMaxDelayMillis, baseDelay);
        return Math.min(Math.max(delayMillis, baseDelay), maxDelay);
    }

    private long multiplyByPowerOfTwoSaturated(long value, int exponent) {
        long result = value;
        for (int i = 0; i < exponent; i++) {
            if (result > Long.MAX_VALUE / 2L) {
                return Long.MAX_VALUE;
            }
            result *= 2L;
        }
        return result;
    }

    private boolean isTimeoutFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof HttpTimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("timed out")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isRateLimitFailure(Throwable error) {
        String message = nestedMessage(error);
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("429")
                || normalized.contains("rate limit")
                || normalized.contains("rate_limit")
                || normalized.contains("too many requests")
                || normalized.contains("resource exhausted")
                || normalized.contains("quota exceeded");
    }

    private boolean isRetryableTransportFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        String message = nestedMessage(error);
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("rst_stream")
                || normalized.contains("goaway")
                || normalized.contains("connection reset")
                || normalized.contains("stream was reset")
                || normalized.contains("temporarily unavailable");
    }

    private boolean isRetryableStatusCode(int statusCode) {
        return statusCode == 408
                || statusCode == 425
                || statusCode == 429
                || statusCode >= 500;
    }

    private boolean isRateLimitStatusCode(int statusCode) {
        return statusCode == 429;
    }

    private String nestedMessage(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (StringUtils.hasText(current.getMessage())) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return "";
    }

    private int positiveOrDefault(Integer value, int defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }

    private long positiveOrDefault(Long value, long defaultValue) {
        return value != null && value > 0L ? value : defaultValue;
    }

    private int nonNegativeOrDefault(Integer value, int defaultValue) {
        return value != null && value >= 0 ? value : defaultValue;
    }

    private void sleep(long delayMillis) {
        if (delayMillis <= 0L) {
            return;
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI retry interrupted", e);
        }
    }

    private void applyExtraHeaders(HttpRequest.Builder builder, String extraHeadersJson) {
        if (!StringUtils.hasText(extraHeadersJson)) {
            return;
        }
        try {
            JsonNode headers = mapper.readTree(extraHeadersJson);
            if (!headers.isObject()) {
                return;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = headers.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String value = field.getValue().asText("");
                if (StringUtils.hasText(field.getKey()) && StringUtils.hasText(value)) {
                    builder.header(field.getKey(), value);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String collectSystemText(List<AiMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (AiMessage message : messages) {
            if (!"system".equals(message.getRole())) {
                continue;
            }
            String text = textContent(safeParts(message));
            if (!StringUtils.hasText(text)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(text.trim());
        }
        return sb.toString();
    }

    private List<AiContentPart> safeParts(AiMessage message) {
        return message != null && message.getParts() != null ? message.getParts() : List.of();
    }

    private boolean isTextOnly(List<AiContentPart> parts) {
        for (AiContentPart part : parts) {
            if (part != null && "image".equals(part.getType())) {
                return false;
            }
        }
        return true;
    }

    private String textContent(List<AiContentPart> parts) {
        StringBuilder sb = new StringBuilder();
        for (AiContentPart part : parts) {
            if (part == null || !"text".equals(part.getType()) || !StringUtils.hasText(part.getText())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(part.getText().trim());
        }
        return sb.toString();
    }

    private boolean isUsableObject(JsonNode node) {
        return node != null && node.isObject() && node.size() > 0;
    }

    private boolean aiTraceEnabled() {
        return catalog != null && catalog.getWorkflow() != null
                && Boolean.TRUE.equals(catalog.getWorkflow().getAiTraceEnabled());
    }

    private int aiTraceMaxChars() {
        if (catalog == null || catalog.getWorkflow() == null
                || catalog.getWorkflow().getAiTraceMaxChars() == null) {
            return 200_000;
        }
        return Math.max(catalog.getWorkflow().getAiTraceMaxChars(), 1_000);
    }

    private void logSuccessfulResponse(AiRuntime runtime, int statusCode, JsonNode response) {
        String content = extractAssistantText(response);
        String reasoning = extractOpenAiReasoningText(response);
        JsonNode payload = extractToolPayload(response);
        String finishReason = firstNonBlank(
                response.path("choices").path(0).path("finish_reason").asText(""),
                response.path("candidates").path(0).path("finishReason").asText(""),
                response.path("candidates").path(0).path("finish_reason").asText(""),
                response.path("stop_reason").asText(""));
        long inputTokens = firstPositiveLong(
                response.path("usage").path("prompt_tokens").asLong(0L),
                response.path("usageMetadata").path("promptTokenCount").asLong(0L),
                response.path("usage").path("input_tokens").asLong(0L));
        long outputTokens = firstPositiveLong(
                response.path("usage").path("completion_tokens").asLong(0L),
                response.path("usageMetadata").path("candidatesTokenCount").asLong(0L),
                response.path("usage").path("output_tokens").asLong(0L));
        log.debug("MathVision AI response received, provider={}, model={}, http={}, finishReason={}, "
                        + "hasToolPayload={}, contentChars={}, reasoningChars={}, inputTokens={}, outputTokens={}",
                runtime.providerCode,
                runtime.model,
                statusCode,
                finishReason,
                payload != null && !payload.isNull(),
                content.length(),
                reasoning.length(),
                inputTokens,
                outputTokens);
    }

    private String extractOpenAiReasoningText(JsonNode response) {
        return extractText(response.path("choices").path(0).path("message").get("reasoning_content"));
    }

    private long firstPositiveLong(long... values) {
        if (values == null) {
            return 0L;
        }
        for (long value : values) {
            if (value > 0L) {
                return value;
            }
        }
        return 0L;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean isZhipu(String providerCode) {
        return providerCode != null && "zhipu".equalsIgnoreCase(providerCode);
    }

    private JsonNode firstPresent(JsonNode node, String first, String second) {
        if (node == null) {
            return null;
        }
        JsonNode firstNode = node.get(first);
        return firstNode != null ? firstNode : node.get(second);
    }

    private String resolveBaseUrl(String providerCode, String configured) {
        if (StringUtils.hasText(configured)) {
            return configured.trim();
        }
        String provider = providerCode != null ? providerCode.toLowerCase(Locale.ROOT) : "";
        switch (provider) {
            case "openai":
                return "https://api.openai.com/v1";
            case "moonshot":
                return "https://api.moonshot.cn/v1";
            case "zhipu":
                return "https://open.bigmodel.cn/api/paas/v4";
            case "google":
                return "https://generativelanguage.googleapis.com/v1beta";
            case "anthropic":
                return "https://api.anthropic.com/v1";
            default:
                throw new IllegalArgumentException("Missing model provider baseUrl: " + providerCode);
        }
    }

    private String geminiGenerateUrl(String baseUrl, String model, String apiKey) {
        String base = trimTrailingSlash(baseUrl);
        String encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        String path = base.endsWith("/models")
                ? base + "/" + model + ":generateContent"
                : base + "/models/" + model + ":generateContent";
        return path + "?key=" + encodedKey;
    }

    private String anthropicMessagesUrl(String baseUrl) {
        String base = trimTrailingSlash(baseUrl);
        if (base.endsWith("/messages")) {
            return base;
        }
        return base + "/messages";
    }

    private String trimTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    private String brief(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 1000 ? body.substring(0, 1000) : body;
    }

    private static final class JsonExtraction {
        private final JsonNode payload;
        private final String failureReason;

        private JsonExtraction(JsonNode payload, String failureReason) {
            this.payload = payload;
            this.failureReason = failureReason == null ? "" : failureReason;
        }

        private static JsonExtraction success(JsonNode payload) {
            return new JsonExtraction(payload, "");
        }

        private static JsonExtraction failure(String failureReason) {
            return new JsonExtraction(null, failureReason);
        }
    }

    private static final class TextExtraction {
        private final String text;
        private final String failureReason;

        private TextExtraction(String text, String failureReason) {
            this.text = text == null ? "" : text;
            this.failureReason = failureReason == null ? "" : failureReason;
        }

        private static TextExtraction success(String text) {
            return new TextExtraction(text, "");
        }

        private static TextExtraction failure(String failureReason) {
            return new TextExtraction("", failureReason);
        }
    }

    private boolean canForceSingleToolChoice(AiRuntime runtime) {
        if (runtime == null) {
            return true;
        }
        String model = runtime.model != null ? runtime.model.trim().toLowerCase(Locale.ROOT) : "";
        return !model.startsWith("kimi-k2.") || "disabled".equalsIgnoreCase(runtime.thinking);
    }

    public static final class CodeResponse {
        private final JsonNode payload;
        private final String code;
        private final String assistantText;
        private final String failureReason;
        private final int apiCalls;

        private CodeResponse(JsonNode payload,
                             String code,
                             String assistantText,
                             String failureReason,
                             int apiCalls) {
            this.payload = payload;
            this.code = code == null ? "" : code;
            this.assistantText = assistantText == null ? "" : assistantText;
            this.failureReason = failureReason == null ? "" : failureReason;
            this.apiCalls = apiCalls;
        }

        public JsonNode getPayload() {
            return payload;
        }

        public String getCode() {
            return code;
        }

        public String getAssistantText() {
            return assistantText;
        }

        public String getFailureReason() {
            return failureReason;
        }

        public int getApiCalls() {
            return apiCalls;
        }

        public boolean hasCode() {
            return StringUtils.hasText(code);
        }

        private CodeResponse withCombinedFailure(CodeResponse fallback) {
            boolean hasToolFailure = StringUtils.hasText(failureReason);
            boolean hasFallbackFailure = fallback != null && StringUtils.hasText(fallback.failureReason);
            String combined;
            if (hasToolFailure && hasFallbackFailure) {
                combined = "Tool response extraction failed: " + failureReason
                        + "; plain-text retry failed: " + fallback.failureReason;
            } else if (hasToolFailure) {
                combined = "Tool response extraction failed: " + failureReason;
            } else if (hasFallbackFailure) {
                combined = "Plain-text retry failed: " + fallback.failureReason;
            } else {
                combined = "AI response did not contain usable code";
            }
            return new CodeResponse(
                    payload != null ? payload : fallback != null ? fallback.payload : null,
                    code,
                    StringUtils.hasText(assistantText) ? assistantText : fallback != null ? fallback.assistantText : "",
                    combined,
                    fallback != null ? fallback.apiCalls : apiCalls);
        }
    }

    private interface HeaderCustomizer {
        void apply(HttpRequest.Builder builder);
    }

    private final class AiHttpException extends RuntimeException {
        private final int statusCode;
        private final String body;

        private AiHttpException(int statusCode, String body) {
            super("AI API HTTP " + statusCode + ": " + brief(body));
            this.statusCode = statusCode;
            this.body = body;
        }

        private int getStatusCode() {
            return statusCode;
        }

        @SuppressWarnings("unused")
        private String getBody() {
            return body;
        }
    }

    private static final class AiRuntime {
        private final Long taskId;
        private final String providerCode;
        private final String protocolCode;
        private final String baseUrl;
        private final String model;
        private final String apiKey;
        private final double temperature;
        private final Double topP;
        private final int maxOutputTokens;
        private final int promptInputBudgetTokens;
        private final String extraHeadersJson;
        private final int requestTimeoutSeconds;
        private final int timeoutRetryAttempts;
        private final double timeoutRetryMultiplier;
        private final int maxRequestTimeoutSeconds;
        private final int emptyResponseRetries;
        private final int transientFailureRetries;
        private final long transientRetryBaseDelayMillis;
        private final long transientRetryMaxDelayMillis;
        private final int rateLimitRetries;
        private final long rateLimitBaseDelayMillis;
        private final long rateLimitMaxDelayMillis;
        private final double rateLimitJitterRatio;
        private final boolean reasoningContentFallback;
        private final boolean adaptiveThinking;
        private final String effort;
        private final String thinking;

        private AiRuntime(Long taskId,
                          String providerCode,
                          String protocolCode,
                          String baseUrl,
                          String model,
                          String apiKey,
                          double temperature,
                          Double topP,
                          int maxOutputTokens,
                          int promptInputBudgetTokens,
                          String extraHeadersJson,
                          int requestTimeoutSeconds,
                          int timeoutRetryAttempts,
                          double timeoutRetryMultiplier,
                          int maxRequestTimeoutSeconds,
                          int emptyResponseRetries,
                          int transientFailureRetries,
                          long transientRetryBaseDelayMillis,
                          long transientRetryMaxDelayMillis,
                          int rateLimitRetries,
                          long rateLimitBaseDelayMillis,
                          long rateLimitMaxDelayMillis,
                          double rateLimitJitterRatio,
                          boolean reasoningContentFallback,
                          boolean adaptiveThinking,
                          String effort,
                          String thinking) {
            this.taskId = taskId;
            this.providerCode = providerCode;
            this.protocolCode = protocolCode;
            this.baseUrl = baseUrl;
            this.model = model;
            this.apiKey = apiKey;
            this.temperature = temperature;
            this.topP = topP;
            this.maxOutputTokens = maxOutputTokens;
            this.promptInputBudgetTokens = promptInputBudgetTokens;
            this.extraHeadersJson = extraHeadersJson;
            this.requestTimeoutSeconds = requestTimeoutSeconds;
            this.timeoutRetryAttempts = timeoutRetryAttempts;
            this.timeoutRetryMultiplier = timeoutRetryMultiplier;
            this.maxRequestTimeoutSeconds = maxRequestTimeoutSeconds;
            this.emptyResponseRetries = emptyResponseRetries;
            this.transientFailureRetries = transientFailureRetries;
            this.transientRetryBaseDelayMillis = transientRetryBaseDelayMillis;
            this.transientRetryMaxDelayMillis = transientRetryMaxDelayMillis;
            this.rateLimitRetries = rateLimitRetries;
            this.rateLimitBaseDelayMillis = rateLimitBaseDelayMillis;
            this.rateLimitMaxDelayMillis = rateLimitMaxDelayMillis;
            this.rateLimitJitterRatio = rateLimitJitterRatio;
            this.reasoningContentFallback = reasoningContentFallback;
            this.adaptiveThinking = adaptiveThinking;
            this.effort = effort;
            this.thinking = thinking;
        }
    }
}
