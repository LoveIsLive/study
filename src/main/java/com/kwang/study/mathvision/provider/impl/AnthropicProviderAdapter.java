package com.kwang.study.mathvision.provider.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.kwang.study.mathvision.enums.ProviderEnum;
import com.kwang.study.mathvision.provider.AbstractHttpProviderAdapter;
import com.kwang.study.mathvision.provider.ProviderProbeResult;
import org.springframework.stereotype.Component;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic: GET {base}/models, 鉴权用 x-api-key + anthropic-version 头。
 */
@Component
public class AnthropicProviderAdapter extends AbstractHttpProviderAdapter {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    @Override
    public ProviderEnum provider() {
        return ProviderEnum.ANTHROPIC;
    }

    @Override
    public ProviderProbeResult testCredential(String apiKey, String baseUrl) {
        return listModels(apiKey, baseUrl);
    }

    @Override
    public ProviderProbeResult testModel(String apiKey, String baseUrl, String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return ProviderProbeResult.fail("模型名称不能为空");
        }
        try {
            String url = resolveBaseUrl(baseUrl) + "/messages";
            String body = MAPPER.createObjectNode()
                    .put("model", modelName.trim())
                    .put("max_tokens", 1)
                    .set("messages", MAPPER.createArrayNode()
                            .add(MAPPER.createObjectNode()
                                    .put("role", "user")
                                    .put("content", "你好")))
                    .toString();
            HttpResponse<String> resp = postJson(url, body, new String[]{
                    "x-api-key", apiKey,
                    "anthropic-version", ANTHROPIC_VERSION
            });
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                if (hasNonEmptyArray(resp.body(), "content")) {
                    return ProviderProbeResult.ok("API Key 和模型可用");
                }
                return ProviderProbeResult.fail("模型接口响应缺少 content 字段");
            }
            return ProviderProbeResult.fail(briefError(resp.statusCode(), resp.body()));
        } catch (Exception e) {
            log.warn("Anthropic model probe failed: {}", e.getMessage());
            return ProviderProbeResult.fail(e.getMessage());
        }
    }

    @Override
    public ProviderProbeResult listModels(String apiKey, String baseUrl) {
        try {
            String url = resolveBaseUrl(baseUrl) + "/models";
            HttpResponse<String> resp = getJson(url, new String[]{
                    "x-api-key", apiKey,
                    "anthropic-version", ANTHROPIC_VERSION
            });
            if (resp.statusCode() == 200) {
                return ProviderProbeResult.ok("API Key 可用", parseModels(resp.body()));
            }
            return ProviderProbeResult.fail(briefError(resp.statusCode(), resp.body()));
        } catch (Exception e) {
            log.warn("Anthropic listModels failed: {}", e.getMessage());
            return ProviderProbeResult.fail(e.getMessage());
        }
    }

    /** Anthropic 返回 { "data": [ { "id": "...", "display_name": "..." } ] };
     *  官方仅 id/display_name, 部分兼容代理附带 max_input_tokens/max_tokens, 存在则解析。 */
    private List<ProviderProbeResult.ProviderModel> parseModels(String body) throws Exception {
        List<ProviderProbeResult.ProviderModel> models = new ArrayList<>();
        JsonNode root = MAPPER.readTree(body);
        JsonNode data = root.get("data");
        if (data != null && data.isArray()) {
            for (JsonNode m : data) {
                String id = m.path("id").asText("");
                if (!id.isBlank()) {
                    String display = m.path("display_name").asText(id);
                    ProviderProbeResult.ProviderModel model = new ProviderProbeResult.ProviderModel(id, display);
                    model.setContextWindow(firstInt(m, "max_input_tokens", "context_window", "context_length"));
                    model.setMaxOutputTokens(firstInt(m, "max_output_tokens", "max_tokens"));
                    models.add(model);
                }
            }
        }
        return models;
    }
}
