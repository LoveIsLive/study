package com.kwang.study.auth.dto.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LoginResultDTO {
    private String token;
    private Boolean needPasswordChange;
}