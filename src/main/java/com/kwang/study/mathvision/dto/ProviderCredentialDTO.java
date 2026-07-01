package com.kwang.study.mathvision.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 设置 / 更新厂家 API Key 请求。
 */
@Data
public class ProviderCredentialDTO {
    @NotBlank(message = "API Key cannot be empty")
    private String apiKey;
}
