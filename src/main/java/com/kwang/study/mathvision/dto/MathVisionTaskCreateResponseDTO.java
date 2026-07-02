package com.kwang.study.mathvision.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 创建任务响应。
 */
@Data
@Builder
public class MathVisionTaskCreateResponseDTO {
    private Long taskId;
    private String sessionId;
    private String title;
    private String status;
    private String currentStage;
    private String mode;
    private String outputTarget;
    private String providerCode;
    private String modelName;
    private Integer currentVersion;
    private Boolean autoStart;
}
