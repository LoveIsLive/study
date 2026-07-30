package com.kwang.study.mathvision.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 任务列表项。
 */
@Data
@Builder
public class MathVisionTaskItemVO {
    private Long taskId;
    private String sessionId;
    private String title;
    private String status;
    private String currentStage;
    private String mode;
    private String outputTarget;
    private String providerCode;
    private String modelName;
    private Boolean cancelRequested;
    private String finalArtifactType;
    private Long squareShareId;
    private String createTime;
    private String updateTime;
}
