package com.kwang.study.mathvision.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MathVisionTaskEventVO {
    @Builder.Default
    private String type = "MATHVISION_TASK";
    private String event;
    private Long taskId;
    private String sessionId;
    private String title;
    private String status;
    private String currentStage;
    private String failedStage;
    private String errorType;
    private String errorMessage;
    private String mode;
    private String outputTarget;
    private String providerCode;
    private String modelName;
    private Integer currentVersion;
    private String lastConfirmedStage;
    private Boolean cancelRequested;
    private String finalArtifactPath;
    private String finalArtifactType;
    private String createTime;
    private String updateTime;
}
