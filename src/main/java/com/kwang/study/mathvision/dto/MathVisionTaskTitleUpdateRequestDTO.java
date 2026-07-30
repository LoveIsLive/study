package com.kwang.study.mathvision.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class MathVisionTaskTitleUpdateRequestDTO {

    @NotBlank(message = "任务标题不能为空")
    @Size(max = 255, message = "任务标题不能超过 255 个字符")
    private String title;
}
