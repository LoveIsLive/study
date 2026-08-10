package com.kwang.study.mathvision.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 厂家配置状态。
 */
@Data
@Builder
public class ProviderInfoVO {
    private String providerCode;
    private String providerName;
    private Boolean configured;
    /** enabled/disabled/invalid/not_configured */
    private String status;
    /** 脱敏后的 API Key, 仅展示; 永不返回明文 */
    private String apiKeyMasked;
    private String lastTestTime;
    private Boolean custom;
    /** openai/anthropic/google，仅自定义供应商返回。 */
    private String compatibilityType;
    private String baseUrl;
    private String modelName;
    private Boolean supportVision;
    private Integer contextWindow;
    private Integer maxOutputTokens;
    private Double temperature;
    private Double topP;
}
