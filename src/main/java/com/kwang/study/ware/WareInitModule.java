package com.kwang.study.ware;

import com.kwang.study.organization.pojo.Classes;
import com.kwang.study.enums.FileStorageModuleNameEnum;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.organization.service.ClassesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class WareInitModule implements ApplicationRunner {
    @Autowired
    private FileStorageService fileStorageService;
    @Autowired
    private ClassesService classesService;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 1. 创建课程仓库根目录
        try {
            fileStorageService.createDirectory(FileStorageModuleNameEnum.WARE_NAME.getModuleName());
        } catch (Exception e) {
            log.warn(e.getMessage());
        }

        // 2. 创建所有班级目录
        List<Classes> allClasses = classesService.getAllClasses();
        for (Classes classes : allClasses) {
            try {
                // 以id为路径，班级名可变
                fileStorageService.createDirectory(FileStorageModuleNameEnum.WARE_NAME
                        .getModuleName() + "/" + classes.getId());
            } catch (Exception e) {
                log.warn(e.getMessage());
            }
        }
    }

}
