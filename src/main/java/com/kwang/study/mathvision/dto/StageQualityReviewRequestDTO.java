package com.kwang.study.mathvision.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class StageQualityReviewRequestDTO {

    @NotNull(message = "阶段版本不能为空")
    private Integer version;
}
