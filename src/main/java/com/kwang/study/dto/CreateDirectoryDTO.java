package com.kwang.study.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

@Data
public class CreateDirectoryDTO {
    @NotBlank(message = "Name is required")
    private String name;

    private Long parentId;

    @Pattern(regexp = "[r-][w-][x-][r-][w-][x-][r-][w-][x-]", message = "Permissions must be in rwxrwxrwx format")
    private String permissions = "rwxrwxrwx";
}
