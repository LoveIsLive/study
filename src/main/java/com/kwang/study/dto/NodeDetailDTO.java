package com.kwang.study.dto;

import com.kwang.study.pojo.Node;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 包含节点和MIME类型名称的详细信息的数据传输对象
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NodeDetailDTO extends Node {

    private static final long serialVersionUID = -30308044539747326L;
    /**
     * 文件的MIME类型名称 (e.g., "application/json")
     */
    private String mimeTypeName;
}