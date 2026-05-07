package com.kwang.study.organization.pojo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 对应 course_guests 表的实体类
 */
@Data
public class CourseGuest {
    private Long id;
    private Long courseId;
    private Long userId;
    private Long classId;
    private LocalDateTime grantTime;
}