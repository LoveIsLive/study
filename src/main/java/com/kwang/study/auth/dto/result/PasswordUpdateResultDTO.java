package com.kwang.study.auth.dto.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PasswordUpdateResultDTO {
    private Boolean success;
    private String errorMessage;
}
