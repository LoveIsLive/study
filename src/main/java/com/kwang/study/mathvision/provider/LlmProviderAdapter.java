package com.kwang.study.mathvision.provider;

import com.kwang.study.mathvision.enums.ProviderEnum;

/**
 * 模型厂家适配器。
 * 本阶段只需要"测试凭据"和"拉模型列表"两项能力;
 * 后续工作流接入时再扩展 createClient 等。
 */
public interface LlmProviderAdapter {

    ProviderEnum provider();

    /** 测试 API Key 是否可用 (优先通过模型列表接口探活)。 */
    ProviderProbeResult testCredential(String apiKey, String baseUrl);

    /**
     * Test a user-declared compatible model through its real generation
     * endpoint. Providers that do not need a model-specific probe retain the
     * existing credential test behavior.
     */
    default ProviderProbeResult testModel(String apiKey, String baseUrl, String modelName) {
        return testCredential(apiKey, baseUrl);
    }

    /** 拉取该厂家当前可用模型列表。 */
    ProviderProbeResult listModels(String apiKey, String baseUrl);
}
