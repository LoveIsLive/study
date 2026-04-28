package com.kwang.study.course;

import com.kwang.study.enums.FileStorageModuleNameEnum;
import com.kwang.study.fs.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CourseModule implements ApplicationRunner {
    @Autowired
    private FileStorageService fileStorageService;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 1. 创建封面图片根目录
        try {
            fileStorageService.createDirectory(FileStorageModuleNameEnum.COVERIMAGE_NAME.getModuleName());
        } catch (Exception e) {
            log.warn(e.getMessage());
        }
    }

}
