package com.kwang.study.organization.dto.request;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class SchoolUpdateDTO {
    @NotBlank(message = "学校名称不能为空")
    @Size(min = 2, max = 100, message = "学校名称长度必须在2到100个字符之间")
    private String name;
}