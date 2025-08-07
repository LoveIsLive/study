package com.kwang.study.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


@Component
@ConfigurationProperties(prefix = "kwang")
@Data
public class AppConfig {
    private FileStorageConfig fileStorage;
    private JWTConfig jwt;

    @Data
    public static class FileStorageConfig {
        private String filePath = "/filedata";
        private String chunkPath = "/filedata/chunk";
        private Integer chunkSize = 10 * 1024 * 1024;
    }

    @Data
    public static class JWTConfig {
        private String security;
        private Long expiration;
    }
}