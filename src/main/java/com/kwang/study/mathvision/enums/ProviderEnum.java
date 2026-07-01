package com.kwang.study.mathvision.enums;

/**
 * 系统固定支持的模型厂家。用户只配置 API Key, 不配置自定义 base URL。
 * defaultBaseUrl 为各厂家默认 OpenAI-compatible / 原生接口根地址。
 */
public enum ProviderEnum {

    OPENAI("openai", "OpenAI", "https://api.openai.com/v1"),
    ANTHROPIC("anthropic", "Anthropic", "https://api.anthropic.com/v1"),
    GOOGLE("google", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta"),
    MOONSHOT("moonshot", "月之暗面", "https://api.moonshot.cn/v1"),
    ZHIPU("zhipu", "智谱", "https://open.bigmodel.cn/api/paas/v4");

    private final String code;
    private final String displayName;
    private final String defaultBaseUrl;

    ProviderEnum(String code, String displayName, String defaultBaseUrl) {
        this.code = code;
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }

    /** 校验并返回枚举; 非法返回 null。 */
    public static ProviderEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ProviderEnum p : values()) {
            if (p.code.equalsIgnoreCase(code)) {
                return p;
            }
        }
        return null;
    }

    public static boolean isValid(String code) {
        return fromCode(code) != null;
    }
}
