package com.kwang.study.mathvision.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 统一模型 DTO。
 */
@Data
@Builder
public class LlmModelDTO {
    private String providerCode;
    private String modelName;
    private String displayName;
    private Boolean supportVision;
    private Boolean supportJsonOutput;
    private Boolean supportThinking;
    private Integer contextWindow;
    private Integer maxOutputTokens;
}
