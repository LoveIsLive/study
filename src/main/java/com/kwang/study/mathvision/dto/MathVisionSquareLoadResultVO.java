package com.kwang.study.mathvision.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MathVisionSquareLoadResultVO {
    private Long taskId;
    private String sessionId;
    private String title;
    private String status;
    private String currentStage;
}
