package com.kwang.study.organization.dto.request;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import java.util.List;

@Data
public class SchoolMemberAddDTO {
    /**
     * 用户名列表（原始用户名，不需要带前缀）
     */
    @NotEmpty(message = "用户名列表不能为空")
    private List<String> userNames;

    /**
     * 角色，默认为 ROLE_PRINCIPAL
     */
    private String role = "ROLE_PRINCIPAL";
}