package com.kwang.study.fs.dto.result;

import lombok.Data;

@Data
public class BaseResult {
    private Boolean success;
    private String errorMessage;
}
