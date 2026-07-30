package com.kwang.study.mathvision.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StageOperationResultVO {
    private Long taskId;
    private String status;
    private String currentStage;
    private Integer currentVersion;
    private String stage;
    private Integer stageVersion;
    private String lastConfirmedStage;
    private boolean saved;
    private boolean copyOnWrite;
}
