package com.kwang.study.ware.dto.request;

import com.kwang.study.dto.BaseRequestDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
@EqualsAndHashCode(callSuper = true)
public class MergeChunkRequestDTO extends BaseRequestDTO {
    private static final long serialVersionUID = 4229399497535920234L;

    @NotNull(message = "上传id不能为空")
    private String uploadId;

    @Min(value = 1, message = "总共快数不能小于1")
    @NotNull(message = "总共块数量不能为空")
    private Integer totalChunks;
}
