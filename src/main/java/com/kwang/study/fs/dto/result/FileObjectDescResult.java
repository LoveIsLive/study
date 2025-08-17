package com.kwang.study.fs.dto.result;

import lombok.Data;
import lombok.EqualsAndHashCode;


@Data
@EqualsAndHashCode(callSuper = true)
public class FileObjectDescResult extends GenericObjectResult {
    /**
     * 路径项的大小，以字节为单位，目录设置0
     */
    private Long size;

    /**
     * 文件的mime类型名称
     */
    private String mimeTypeName;
}
