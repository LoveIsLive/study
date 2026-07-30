package com.kwang.study.mathvision.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MathVisionSquarePost {
    private Long id;
    private Long taskId;
    private Integer version;
    private Integer loadCount;
    private LocalDateTime createTime;

    /** 以下字段由广场查询关联现有业务表得出，不存储在广场表。 */
    private Long ownerUserId;
    private String title;
    private String summary;
    private String outputTarget;
    private String artifactPath;
    private String artifactType;
    private String authorName;
}
