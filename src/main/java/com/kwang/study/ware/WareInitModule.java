package com.kwang.study.ware;

import com.kwang.study.enums.FileStorageModuleNameEnum;
import com.kwang.study.fs.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WareInitModule implements ApplicationRunner {
    @Autowired
    private FileStorageService fileStorageService;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 1. 创建课程仓库根目录
        try {
            fileStorageService.createDirectory(FileStorageModuleNameEnum.WARE_NAME.getModuleName());
        } catch (Exception e) {
            log.warn(e.getMessage());
        }
    }

}
