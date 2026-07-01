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
}
