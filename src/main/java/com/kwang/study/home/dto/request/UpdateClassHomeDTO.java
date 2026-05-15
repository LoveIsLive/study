package com.kwang.study.home.dto.request;

import lombok.Data;
import javax.validation.constraints.NotNull;

@Data
public class UpdateClassHomeDTO {

    @NotNull(message = "班级ID不能为空")
    private Long classId;

    private String description;
}