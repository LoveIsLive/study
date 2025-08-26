package com.kwang.study.homework.dto.request;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

@Data
public class FileMetaDTO {
    @NotBlank
    private String fileName;
    @NotBlank
    private String mimeTypeName;
    @NotNull @Positive
    private Long fileSize;
}
