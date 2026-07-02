package com.kwang.study.mathvision.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * adapter 共用的 HTTP 基础设施 (Java 11 HttpClient + Jackson)。
 */
public abstract class AbstractHttpProviderAdapter implements LlmProviderAdapter {

    protected static final Logger log = LoggerFactory.getLogger(AbstractHttpProviderAdapter.class);
    protected static final ObjectMapper MAPPER = new ObjectMapper();

    protected static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /** 解析 base url, 留空则用厂家默认值, 去掉结尾斜杠。 */
    protected String resolveBaseUrl(String baseUrl) {
        String url = (baseUrl == null || baseUrl.isBlank())
                ? provider().getDefaultBaseUrl()
                : baseUrl;
        return url.replaceAll("/+$", "");
    }

    protected HttpResponse<String> getJson(String url, String[] headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET();
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> postJson(String url, String body, String[] headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 解析 OpenAI 风格 { "data": [ { "id": "..." } ] } 模型列表。
     * 官方 OpenAI 返回体只有 id, 但部分兼容厂家 (Moonshot/Zhipu 等) 会附带
     * context_length / max_tokens 等字段, 这里防御性解析, 拿到真值就填入。
     */
    protected List<ProviderProbeResult.ProviderModel> parseOpenAiStyleModels(String body) throws Exception {
        List<ProviderProbeResult.ProviderModel> models = new ArrayList<>();
        JsonNode root = MAPPER.readTree(body);
        JsonNode data = root.get("data");
        if (data != null && data.isArray()) {
            for (JsonNode m : data) {
                String id = m.path("id").asText("");
                if (id.isBlank()) {
                    continue;
                }
                ProviderProbeResult.ProviderModel model = new ProviderProbeResult.ProviderModel(id, id);
                model.setContextWindow(firstInt(m, "context_length", "context_window", "max_context_length"));
                model.setMaxOutputTokens(firstInt(m, "max_output_tokens", "max_tokens", "max_completion_tokens"));
                models.add(model);
            }
        }
        return models;
    }

    /** 依次尝试若干字段名, 返回首个存在且为正整数的值, 都没有则 null。 */
    protected static Integer firstInt(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && v.canConvertToInt() && v.asInt() > 0) {
                return v.asInt();
            }
        }
        return null;
    }

    /** 截断厂商错误体, 避免过长日志/返回。 */
    protected String briefError(int statusCode, String body) {
        String b = body == null ? "" : body.trim();
        if (b.length() > 300) {
            b = b.substring(0, 300) + "...";
        }
        return "HTTP " + statusCode + (b.isEmpty() ? "" : ": " + b);
    }
}
