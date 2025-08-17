package com.kwang.study.fs.pojo;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class NodeDetail extends Node {

    private static final long serialVersionUID = -30308044539747326L;
    /**
     * 文件的MIME类型名称 (e.g., "application/json")
     */
    private String mimeTypeName;
}
