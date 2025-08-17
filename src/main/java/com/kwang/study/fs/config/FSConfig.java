package com.kwang.study.fs.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "kwang.fs")
@Data
public class FSConfig {
    private String filePath = "filedata";
    private String chunkPath = "filedata/chunk";
    private Integer chunkSize = 10 * 1024 * 1024;
}
