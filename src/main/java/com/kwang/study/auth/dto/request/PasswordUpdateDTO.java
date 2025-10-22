package com.kwang.study.auth.dto.request;

import lombok.Data;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;

@Data
public class PasswordUpdateDTO {

    @NotEmpty(message = "旧密码不能为空")
    private String oldPassword;

    @NotEmpty(message = "新密码不能为空")
    @Size(min = 6, max = 20, message = "新密码长度必须在6到20位之间")
    private String newPassword;
}
