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
    /** 系统固定, 用户不可改; 一般留空走默认 */
    private String baseUrl;
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
    /** 可用模型列表缓存 (JSON 文本, 含能力标记 vision/json/thinking/ctx) */
    private String modelsCacheJson;
    /** 模型列表最近同步时间 */
    private LocalDateTime lastSyncTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
