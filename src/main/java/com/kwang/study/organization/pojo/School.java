package com.kwang.study.organization.pojo;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class School {
    private Long id;
    private String name;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // 非数据库字段
    private Integer classCount;
}