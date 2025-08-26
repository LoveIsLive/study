package com.kwang.study.homework.dto.request;

import lombok.Data;
import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import java.util.List;

@Data
public class BatchUploadInitRequestDTO {
    @NotEmpty
    @Valid // 级联校验
    private List<FileMetaDTO> files;
}
