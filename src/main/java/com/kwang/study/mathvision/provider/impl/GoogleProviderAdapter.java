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
    public ProviderProbeResult testModel(String apiKey, String baseUrl, String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return ProviderProbeResult.fail("模型名称不能为空");
        }
        try {
            String base = resolveBaseUrl(baseUrl);
            String path = base.endsWith("/models")
                    ? base + "/" + modelName.trim() + ":generateContent"
                    : base + "/models/" + modelName.trim() + ":generateContent";
            String url = path + "?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
            String body = MAPPER.createObjectNode()
                    .set("contents", MAPPER.createArrayNode()
                            .add(MAPPER.createObjectNode()
                                    .set("parts", MAPPER.createArrayNode()
                                            .add(MAPPER.createObjectNode().put("text", "你好")))))
                    .toString();
            HttpResponse<String> resp = postJson(url, body, new String[]{});
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                if (hasNonEmptyArray(resp.body(), "candidates")) {
                    return ProviderProbeResult.ok("API Key 和模型可用");
                }
                return ProviderProbeResult.fail("模型接口响应缺少 candidates 字段");
            }
            return ProviderProbeResult.fail(briefError(resp.statusCode(), resp.body()));
        } catch (Exception e) {
            log.warn("Google model probe failed: {}", e.getMessage());
            return ProviderProbeResult.fail(e.getMessage());
        }
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

    /**
     * name 形如 "models/gemini-1.5-pro", 去掉前缀作为 modelName。
     * Google 返回体元数据较丰富, 直接取真值:
     *   displayName        -> 展示名
     *   inputTokenLimit    -> 上下文窗口
     *   outputTokenLimit   -> 最大输出
     *   supportedGenerationMethods 含 generateContent -> 视为对话模型
     */
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
                // 只保留支持 generateContent 的对话模型, 排除 embedding / aqa 等
                if (!supportsGenerateContent(m)) {
                    continue;
                }
                String modelName = name.startsWith("models/") ? name.substring("models/".length()) : name;
                String display = m.path("displayName").asText(modelName);
                ProviderProbeResult.ProviderModel model = new ProviderProbeResult.ProviderModel(modelName, display);
                model.setContextWindow(firstInt(m, "inputTokenLimit"));
                model.setMaxOutputTokens(firstInt(m, "outputTokenLimit"));
                models.add(model);
            }
        }
        return models;
    }

    private boolean supportsGenerateContent(JsonNode m) {
        JsonNode methods = m.get("supportedGenerationMethods");
        if (methods == null || !methods.isArray()) {
            return true; // 字段缺失时不做过滤, 交由上层能力推断
        }
        for (JsonNode method : methods) {
            String s = method.asText("");
            if ("generateContent".equals(s) || "bidiGenerateContent".equals(s)) {
                return true;
            }
        }
        return false;
    }
}
