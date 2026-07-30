package com.kwang.study.mathvision.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MathVisionVersionItemVO {
    private Integer version;
    private Integer baseVersion;
    private String branchStage;
    private String latestStage;
    private String changeSource;
    private String changeSummary;
    private String finalArtifactType;
    private Boolean isCurrent;
    private String createTime;
    private String updateTime;
}
