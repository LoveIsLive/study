package com.kwang.study.mathvision.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 任务详情。
 */
@Data
@Builder
public class MathVisionTaskDetailVO {
    private Long taskId;
    private String sessionId;
    private String title;
    private String inputText;
    private String inputSourceType;
    private List<InputAssetDTO> inputAssets;
    private String mode;
    private String outputTarget;
    private String status;
    private String currentStage;
    private String failedStage;
    private String errorType;
    private String errorMessage;
    private Long selectedModelConfigId;
    private String providerCode;
    private String modelName;
    private Integer currentVersion;
    private String lastConfirmedStage;
    private String finalArtifactPath;
    private String finalArtifactType;
    private String createTime;
    private String updateTime;
}
