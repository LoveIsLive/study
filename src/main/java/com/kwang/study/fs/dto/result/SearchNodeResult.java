package com.kwang.study.fs.dto.result;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SearchNodeResult extends GenericObjectResult {
    private String fullPath;
}
