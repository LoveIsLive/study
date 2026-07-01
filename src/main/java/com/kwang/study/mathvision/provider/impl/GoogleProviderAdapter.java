package com.kwang.study.mathvision.provider.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.kwang.study.mathvision.enums.ProviderEnum;
import com.kwang.study.mathvision.provider.AbstractHttpProviderAdapter;
import com.kwang.study.mathvision.provider.ProviderProbeResult;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Gemini: GET {base}/models?key=API_KEY, 返回 { "models": [ { "name": "models/gemini-..." } ] }。
 */
@Component
public class GoogleProviderAdapter extends AbstractHttpProviderAdapter {

    @Override
    public ProviderEnum provider() {
        return ProviderEnum.GOOGLE;
    }

    @Override
    public ProviderProbeResult testCredential(String apiKey, String baseUrl) {
        return listModels(apiKey, baseUrl);
    }

    @Override
    public ProviderProbeResult listModels(String apiKey, String baseUrl) {
        try {
            String url = resolveBaseUrl(baseUrl) + "/models?key="
                    + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
            HttpResponse<String> resp = getJson(url, new String[]{});
            if (resp.statusCode() == 200) {
                return ProviderProbeResult.ok("API Key 可用", parseModels(resp.body()));
            }
            return ProviderProbeResult.fail(briefError(resp.statusCode(), resp.body()));
        } catch (Exception e) {
            log.warn("Google listModels failed: {}", e.getMessage());
            return ProviderProbeResult.fail(e.getMessage());
        }
    }

    /** name 形如 "models/gemini-1.5-pro", 去掉前缀作为 modelName; displayName 取 displayName 字段。 */
    private List<ProviderProbeResult.ProviderModel> parseModels(String body) throws Exception {
        List<ProviderProbeResult.ProviderModel> models = new ArrayList<>();
        JsonNode root = MAPPER.readTree(body);
        JsonNode arr = root.get("models");
        if (arr != null && arr.isArray()) {
            for (JsonNode m : arr) {
                String name = m.path("name").asText("");
                if (name.isBlank()) {
                    continue;
                }
                String modelName = name.startsWith("models/") ? name.substring("models/".length()) : name;
                String display = m.path("displayName").asText(modelName);
                models.add(new ProviderProbeResult.ProviderModel(modelName, display));
            }
        }
        return models;
    }
}
