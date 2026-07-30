package com.kwang.study.mathvision.dto;

import lombok.Data;

import javax.validation.constraints.Pattern;

@Data
public class MathVisionTaskRuntimeSettingsRequestDTO {

    @Pattern(regexp = "auto|manual", message = "运行模式必须是 auto 或 manual")
    private String mode;

    private String providerCode;

    private String modelName;
}
