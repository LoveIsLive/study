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

    /**
     * 所属学校ID。
     * 对于 Admin 用户，此字段必填。
     * 对于 学校负责人，前端可传可不传，后端会强制使用其所属学校ID。
     */
    private Long schoolId;
}