package com.kwang.study.mathvision.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MathVisionSquareItemVO {
    private Long shareId;
    private Long taskId;
    private Integer version;
    private String title;
    private String summary;
    private String authorName;
    private String outputTarget;
    private String artifactPath;
    private String artifactType;
    private Integer loadCount;
    private Boolean mine;
    private String createTime;
}
