package com.kwang.study.pojo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FileChunk {
    private Long fileId;
    private Integer chunkIndex;
    private String key;
    private Integer status;
    private LocalDateTime createTime;
}