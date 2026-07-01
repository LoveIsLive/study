package com.kwang.study.mathvision.dto;

import lombok.Builder;
import lombok.Data;

/**
 * API Key 测试结果。
 */
@Data
@Builder
public class CredentialTestResultVO {
    private String providerCode;
    private Boolean success;
    /** enabled/invalid */
    private String status;
    private String message;
}
