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

    /**
     * adapter 从厂家 /models 返回体解析出的模型条目。
     * 能力 / 窗口字段可空: 非空表示厂家接口给了真值, 为空则由 service 侧启发式兜底。
     */
    public static class ProviderModel {
        private final String modelName;
        private final String displayName;
        private Integer contextWindow;
        private Integer maxOutputTokens;
        private Boolean supportVision;
        private Boolean supportJsonOutput;
        private Boolean supportThinking;

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

        public Integer getContextWindow() {
            return contextWindow;
        }

        public ProviderModel setContextWindow(Integer contextWindow) {
            this.contextWindow = contextWindow;
            return this;
        }

        public Integer getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public ProviderModel setMaxOutputTokens(Integer maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
            return this;
        }

        public Boolean getSupportVision() {
            return supportVision;
        }

        public ProviderModel setSupportVision(Boolean supportVision) {
            this.supportVision = supportVision;
            return this;
        }

        public Boolean getSupportJsonOutput() {
            return supportJsonOutput;
        }

        public ProviderModel setSupportJsonOutput(Boolean supportJsonOutput) {
            this.supportJsonOutput = supportJsonOutput;
            return this;
        }

        public Boolean getSupportThinking() {
            return supportThinking;
        }

        public ProviderModel setSupportThinking(Boolean supportThinking) {
            this.supportThinking = supportThinking;
            return this;
        }
    }
}
