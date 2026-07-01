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

    /** Anthropic 返回 { "data": [ { "id": "...", "display_name": "..." } ] }。 */
    private List<ProviderProbeResult.ProviderModel> parseModels(String body) throws Exception {
        List<ProviderProbeResult.ProviderModel> models = new ArrayList<>();
        JsonNode root = MAPPER.readTree(body);
        JsonNode data = root.get("data");
        if (data != null && data.isArray()) {
            for (JsonNode m : data) {
                String id = m.path("id").asText("");
                if (!id.isBlank()) {
                    String display = m.path("display_name").asText(id);
                    models.add(new ProviderProbeResult.ProviderModel(id, display));
                }
            }
        }
        return models;
    }
}
