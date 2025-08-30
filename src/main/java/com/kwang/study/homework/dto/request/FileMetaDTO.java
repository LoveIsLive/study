package com.kwang.study.homework.dto.request;

import lombok.Data;

import javax.validation.constraints.*;

@Data
public class FileMetaDTO {
    @NotBlank(message = "文件名不能为空")
    @Size(min = 1, max = 255, message = "文件名长度必须在1-255个字符之间")
    @Pattern(
            regexp = "^[^<>:\"/\\\\|?*]+$",
            message = "文件名不能包含非法字符 <>:\"/\\|?*"
    )
    private String fileName;
    @NotBlank
    private String mimeTypeName;
    @NotNull @Positive
    private Long fileSize;
}
