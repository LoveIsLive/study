package com.kwang.study.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


@Component
@ConfigurationProperties(prefix = "kwang")
@Data
public class AppConfig {
    private FileStorageConfig fileStorage;


    @Data
    public static class FileStorageConfig {
        private String filePath;
    }
}