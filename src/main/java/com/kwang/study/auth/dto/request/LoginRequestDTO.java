package com.kwang.study.auth.dto.request;


import com.kwang.study.dto.BaseRequestDTO;

import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.validation.constraints.NotBlank;

@Data
@EqualsAndHashCode(callSuper = true)
public class LoginRequestDTO extends BaseRequestDTO {
    private static final long serialVersionUID = 1369154046899685120L;

    // 非必填，如果是Admin登录则不传
    private Long schoolId;

    @NotBlank(message = "账号不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
