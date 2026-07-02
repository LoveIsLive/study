package com.kwang.study.mathvision.controller;

import com.kwang.study.constant.ApiPrefixConstant;
import com.kwang.study.enums.FileStorageModuleNameEnum;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.utils.BaseFileUploadController;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * MathVision 分块上传控制器。大文件先经此走 batch-init / chunk / merge,
 * 再由创建任务请求通过 uploadFiles 引用。
 */
@RestController
@RequestMapping(ApiPrefixConstant.MATHVISION_BASE_PREFIX + "/upload")
public class MathVisionFileUploadController extends BaseFileUploadController {

    public MathVisionFileUploadController(FileStorageService fileStorageService,
                                          RedisTemplate<String, Object> redisTemplate) {
        super(fileStorageService, redisTemplate);
    }

    @Override
    public String produceFilePath(String fileName) {
        String ext = fileName != null && fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.'))
                : "";
        return FileStorageModuleNameEnum.MATHVISION_NAME.getModuleName() + "/" + UUID.randomUUID() + ext;
    }
}
