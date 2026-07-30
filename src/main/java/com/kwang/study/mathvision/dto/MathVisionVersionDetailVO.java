package com.kwang.study.mathvision.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MathVisionVersionDetailVO {
    private Long taskId;
    private Integer version;
    private Integer baseVersion;
    private String branchStage;
    private String latestStage;
    private String changeSource;
    private String changeSummary;
    private Boolean isCurrent;

    private Integer problemNormalizationVersion;
    private Integer reasoningGraphVersion;
    private Integer visualStoryboardVersion;
    private Integer codeGenerationVersion;
    private Integer renderResultVersion;

    private String problemBundleJson;
    private String dagGraphJson;
    private String narrativeJson;
    private String codeText;
    private String codeFormat;
    private String renderResultJson;
    private String artifactPath;
    private String finalArtifactType;
    private String workflowSummaryJson;
    private String createTime;
    private String updateTime;
}
