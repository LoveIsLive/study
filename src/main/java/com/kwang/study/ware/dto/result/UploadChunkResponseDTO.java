package com.kwang.study.ware.dto.result;

import lombok.Data;

@Data
public class UploadChunkResponseDTO {
    private Boolean merged;
    private Integer uploadNum;
    private Boolean success;
    private String errorMessage;
}
