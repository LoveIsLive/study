package com.kwang.study.pojo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Node {
    private Long id;
    private Long parentId;
    private String name;
    private Integer type; // 0: dir, 1: file
    private String permissions;
    private Integer size;
    private LocalDateTime createTime;
    private LocalDateTime modifyTime;
    private String refPath;
    private String hash;
}
