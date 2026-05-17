package com.kwang.study.course.dto.request;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class CourseDTO {
    @NotBlank(message = "课程名称不能为空")
    private String name;
    @NotNull
    private Long classId;
    private String description;
}