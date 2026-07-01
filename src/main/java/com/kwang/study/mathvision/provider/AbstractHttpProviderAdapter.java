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

    /** 解析 OpenAI 风格 { "data": [ { "id": "..." } ] } 模型列表。 */
    protected List<ProviderProbeResult.ProviderModel> parseOpenAiStyleModels(String body) throws Exception {
        List<ProviderProbeResult.ProviderModel> models = new ArrayList<>();
        JsonNode root = MAPPER.readTree(body);
        JsonNode data = root.get("data");
        if (data != null && data.isArray()) {
            for (JsonNode m : data) {
                String id = m.path("id").asText("");
                if (!id.isBlank()) {
                    models.add(new ProviderProbeResult.ProviderModel(id, id));
                }
            }
        }
        return models;
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
