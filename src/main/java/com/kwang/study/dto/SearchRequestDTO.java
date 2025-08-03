package com.kwang.study.dto;

import lombok.Data;

@Data
public class SearchRequestDTO {
    private Long startNodeId; // 从哪个目录开始搜索，为null则从根目录开始
    private String namePattern; // 模糊查询的名称模式
}
