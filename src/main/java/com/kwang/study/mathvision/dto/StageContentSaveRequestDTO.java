package com.kwang.study.mathvision.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class StageContentSaveRequestDTO {

    @NotNull(message = "version is required")
    private Integer version;

    @NotNull(message = "content is required")
    private JsonNode content;

    private String comment;
}
