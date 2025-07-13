package com.kwang.study.configuration;

import com.kwang.study.filesystem.FileStorage;
import com.kwang.study.filesystem.LocalFileStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.URISyntaxException;

@Configuration
public class FileStorageConfiguration {
    @Autowired
    private AppConfig appConfig;

    @Bean
    public FileStorage fileStorage() throws URISyntaxException, IOException {
        return new LocalFileStorage(appConfig.getFileStorage().getFilePath());
    }
}
