package com.kwang.study.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.InputStream;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileItem {
    // 文件名
    private String fileName;
    // 文件类型
    private String mimeTypeName;
    // 文件大小
    private Long fileSize;
    // 文件完整路径
    private String path;
    // 文件内容
    @JsonIgnore
    private InputStream stream;
    // 其他属性
    private Map<String, Object> attributes;
}
