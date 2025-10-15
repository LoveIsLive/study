package com.kwang.study.auth.pojo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class Classes {
    private Long id;
    private String name;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    // 一个班级可以有多名成员
    private List<ClassMember> members;
}
