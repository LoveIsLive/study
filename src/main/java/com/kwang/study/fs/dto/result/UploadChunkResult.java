package com.kwang.study.fs.dto.result;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class UploadChunkResult extends GenericObjectResult {
    private Boolean merged;
    private Integer uploadNum;
}
