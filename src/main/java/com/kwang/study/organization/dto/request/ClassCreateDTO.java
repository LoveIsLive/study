package com.kwang.study.organization.dto.request;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 用于创建和更新班级的数据传输对象
 */
@Data
public class ClassCreateDTO {

    @NotBlank(message = "班级名称不能为空")
    @Size(min = 2, max = 100, message = "班级名称长度必须在2到100个字符之间")
    private String name;
}