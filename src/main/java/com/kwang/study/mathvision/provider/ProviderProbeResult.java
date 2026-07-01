package com.kwang.study.mathvision.provider;

import java.util.List;

/**
 * 测试 / 列模型的轻量结果载体。
 */
public class ProviderProbeResult {

    private final boolean success;
    private final String message;
    private final List<ProviderModel> models;

    private ProviderProbeResult(boolean success, String message, List<ProviderModel> models) {
        this.success = success;
        this.message = message;
        this.models = models;
    }

    public static ProviderProbeResult ok(String message, List<ProviderModel> models) {
        return new ProviderProbeResult(true, message, models);
    }

    public static ProviderProbeResult ok(String message) {
        return new ProviderProbeResult(true, message, List.of());
    }

    public static ProviderProbeResult fail(String message) {
        return new ProviderProbeResult(false, message, List.of());
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public List<ProviderModel> getModels() {
        return models;
    }

    /** adapter 返回的原始模型条目, 由 service 二次加工成 LlmModelDTO。 */
    public static class ProviderModel {
        private final String modelName;
        private final String displayName;

        public ProviderModel(String modelName, String displayName) {
            this.modelName = modelName;
            this.displayName = displayName;
        }

        public String getModelName() {
            return modelName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
