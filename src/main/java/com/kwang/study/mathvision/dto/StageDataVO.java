package com.kwang.study.mathvision.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StageDataVO {
    private Long taskId;
    private String sessionId;
    private String status;
    private String currentStage;
    private Integer currentVersion;
    private String stage;
    private Integer stageVersion;
    private String artifactJson;
    private String resultJson;
    private boolean editable;
    private boolean canConfirm;
    private boolean canRegenerate;
    private boolean canAutoEdit;
    private boolean qualityReviewSupported;
    private String qualityReviewStatus;
    private String qualityReviewNode;
    private boolean canRunQualityReview;
}
