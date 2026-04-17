package com.kwang.study.course.dto.request;

import lombok.Data;
import javax.validation.constraints.NotBlank;

@Data
public class CourseDTO {
    @NotBlank(message = "课程名称不能为空")
    private String name;
    private String description;
    private String coverImage;
}