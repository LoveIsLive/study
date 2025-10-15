package com.kwang.study.homework;


import com.kwang.study.auth.mapper.ClassesMapper;
import com.kwang.study.auth.pojo.Classes;
import com.kwang.study.enums.FileStorageModuleNameEnum;
import com.kwang.study.fs.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class HomeworkInitModule implements ApplicationRunner {
    @Autowired
    private FileStorageService fileStorageService;
    @Autowired
    private ClassesMapper classesMapper;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 1. 创建作业区根目录
        try {
            fileStorageService.createDirectory(FileStorageModuleNameEnum.HOMEWORK_NAME.getModuleName());
        } catch (Exception e) {
            log.error(e.getMessage());
        }

        // 2. 创建所有班级目录
        List<Classes> allClasses = classesMapper.findAll();
        for (Classes classes : allClasses) {
            try {
                fileStorageService.createDirectory(FileStorageModuleNameEnum.HOMEWORK_NAME
                        .getModuleName() + "/" + classes.getName());
            } catch (Exception e) {
                log.error(e.getMessage());
            }
        }
    }

}

