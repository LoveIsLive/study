package com.kwang.study.mathvision.pojo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LLM 模型 / API Key 配置 (每用户每厂家一条凭据)。
 * 对应表 llm_model_configs。
 */
@Data
@Builder
public class LlmModelConfig {
    private Long id;
    /** 配置所属用户 ID */
    private Long ownerUserId;
    /** openai/anthropic/google/moonshot/zhipu */
    private String provider;
    /** 应用层加密后的 API Key */
    private String apiKeyEncrypted;
    /** 脱敏展示值, 如 sk-****abcd */
    private String apiKeyMasked;
    /** enabled/disabled/invalid/not_configured */
    private String status;
    private LocalDateTime lastTestTime;
    /** 最近一次测试结果摘要 */
    private String lastTestResult;
    private Double temperature;
    /** 是否启用 thinking */
    private Boolean enableThinking;
    private Double topP;
    /** 供应商要求的额外 Header (JSON 文本) */
    private String extraHeadersJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
