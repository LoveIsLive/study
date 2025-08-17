package com.kwang.study.fs.dto.result;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class MimeTypeResult extends BaseResult {
    private List<String> mimeTypeNames;
}
