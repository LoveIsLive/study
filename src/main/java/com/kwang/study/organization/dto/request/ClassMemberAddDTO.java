package com.kwang.study.organization.dto.request;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 添加班级成员的请求体
 */
@Data
public class ClassMemberAddDTO {
    /**
     * 用户ID列表
     */
    @NotEmpty(message = "用户名列表不能为空")
    private List<String> userNames;

    /**
     * 在班级中扮演的角色
     */
    @NotNull(message = "角色不能为空")
    private String role;
}