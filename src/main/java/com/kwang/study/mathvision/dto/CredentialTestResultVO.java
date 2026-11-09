package com.kwang.study.mathvision.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import com.kwang.study.mathvision.engine.MathVisionCredentialTestStore;

/**
 * API Key 测试结果。
 */
@Data
@Builder
public class CredentialTestResultVO {
    private String providerCode;
    private Boolean success;
    /** Existing credential state; network failures never change it to invalid. */
    private String status;
    private String message;
    private String testId;
    /** pending/completed/unavailable/changed/expired */
    private String testState;
    private List<MathVisionCredentialTestStore.WorkerResult> workerResults;
}
