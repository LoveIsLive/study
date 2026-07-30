package com.kwang.study.mathvision;

import com.kwang.study.enums.FileStorageModuleNameEnum;
import com.kwang.study.fs.exception.PathAlreadyExistsException;
import com.kwang.study.fs.service.FileStorageService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Ensures the MathVision file-storage root exists before uploads are accepted. */
@Component
public class MathVisionInitModule implements ApplicationRunner {

    private final FileStorageService fileStorageService;

    public MathVisionInitModule(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            fileStorageService.createDirectory(FileStorageModuleNameEnum.MATHVISION_NAME.getModuleName());
        } catch (PathAlreadyExistsException ignored) {
            // The module root is persistent, so an existing directory is the expected restart path.
        }
    }
}
