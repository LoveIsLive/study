package com.kwang.study.mathvision.provider.impl;

import com.kwang.study.mathvision.enums.ProviderEnum;
import com.kwang.study.mathvision.provider.AbstractHttpProviderAdapter;
import com.kwang.study.mathvision.provider.ProviderProbeResult;
import org.springframework.stereotype.Component;

import java.net.http.HttpResponse;
import java.util.List;

/**
 * 月之暗面 (Moonshot/Kimi): OpenAI-compatible, GET {base}/models, Authorization: Bearer。
 */
@Component
public class MoonshotProviderAdapter extends AbstractHttpProviderAdapter {

    @Override
    public ProviderEnum provider() {
        return ProviderEnum.MOONSHOT;
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
                    "Authorization", "Bearer " + apiKey
            });
            if (resp.statusCode() == 200) {
                List<ProviderProbeResult.ProviderModel> models = parseOpenAiStyleModels(resp.body());
                return ProviderProbeResult.ok("API Key 可用", models);
            }
            return ProviderProbeResult.fail(briefError(resp.statusCode(), resp.body()));
        } catch (Exception e) {
            log.warn("Moonshot listModels failed: {}", e.getMessage());
            return ProviderProbeResult.fail(e.getMessage());
        }
    }
}
