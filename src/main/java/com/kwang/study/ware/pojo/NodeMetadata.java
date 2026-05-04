package com.kwang.study.ware.pojo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 节点元数据扩展表 实体类
 * 对应数据库表：node_metadata
 */
@Data
@Builder
public class NodeMetadata {

    /**
     * 关联的node.id（主键）
     */
    private Long nodeId;

    /**
     * AI提取的文件主要内容描述
     */
    private String aiSummary;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}