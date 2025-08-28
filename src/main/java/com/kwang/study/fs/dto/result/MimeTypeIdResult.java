package com.kwang.study.fs.dto.result;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @author kwang
 * @date 2025/08/28
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MimeTypeIdResult extends BaseResult {
    private Integer mimeTypeId;
}
