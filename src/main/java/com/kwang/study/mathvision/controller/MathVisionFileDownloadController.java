package com.kwang.study.mathvision.controller;

import com.kwang.study.constant.ApiPrefixConstant;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.utils.BaseFileDownloadController;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPrefixConstant.MATHVISION_BASE_PREFIX + "/download")
@Validated
public class MathVisionFileDownloadController extends BaseFileDownloadController {

    public MathVisionFileDownloadController(FileStorageService fileStorageService,
                                            RedisTemplate<String, Object> redisTemplate) {
        super(fileStorageService, redisTemplate);
    }
}
