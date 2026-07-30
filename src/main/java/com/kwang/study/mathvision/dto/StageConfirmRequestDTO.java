package com.kwang.study.mathvision.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class StageConfirmRequestDTO {

    @NotNull(message = "version is required")
    private Integer version;

    private String comment;
}
