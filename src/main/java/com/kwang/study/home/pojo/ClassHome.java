package com.kwang.study.home.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassHome {
    private Long classId;
    private String coverImage;
    private String description;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}