package com.kwang.study.course.pojo;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Course {
    private Long id;
    private Long classId;
    private Long teacherId;
    private String name;
    private String description;
    private String coverImage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // 连表查询字段，用于前端展示教师姓名
    private String teacherName;
}