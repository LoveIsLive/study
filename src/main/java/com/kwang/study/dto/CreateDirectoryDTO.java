package com.kwang.study.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

@Data
public class CreateDirectoryDTO {
    @NotBlank(message = "目录名称不能为空")
    @Pattern(regexp = "^[^/\\\\:*?\"<>|]+$", message = "目录名称不能包含非法字符")
    private String name;

    private Long parentId;

    private String permissions = "rwxrwxrwx";
}
