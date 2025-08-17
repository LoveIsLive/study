package com.kwang.study.fs.config;

import com.kwang.study.fs.storage.FileStorage;
import com.kwang.study.fs.storage.LocalFileStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class FileStorageConfig {
    @Autowired
    private FSConfig fsConfig;

    @Bean("fileStorage")
    public FileStorage fileStorage() throws IOException {
        return new LocalFileStorage(fsConfig.getFilePath());
    }

    @Bean("chunkStorage")
    public FileStorage chunkStorage() throws IOException {
        return new LocalFileStorage(fsConfig.getChunkPath());
    }
}
