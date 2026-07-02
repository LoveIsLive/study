package com.kwang.study.mathvision.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 某厂家可用模型列表响应。
 */
@Data
@Builder
public class ProviderModelsVO {
    private String providerCode;
    private List<LlmModelDTO> models;
}
