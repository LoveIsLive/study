package com.kwang.study.mathvision.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

@Data
public class StageAutoEditRequestDTO {

    @NotNull(message = "基线阶段版本不能为空")
    private Integer baseStageVersion;

    @NotBlank(message = "自动编辑意见不能为空")
    @Size(max = 4000, message = "自动编辑意见不能超过 4000 个字符")
    private String instruction;
}
