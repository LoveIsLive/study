package com.kwang.study.homework.controller;

import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.utils.BaseFileDownloadController;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


import static com.kwang.study.constant.ApiPrefixConstant.ATTACHE_DOWNLOAD_BASE_PREFIX;

@RestController
@RequestMapping(ATTACHE_DOWNLOAD_BASE_PREFIX)
@Validated
public class AttacheFileDownloadController extends BaseFileDownloadController {

    public AttacheFileDownloadController(FileStorageService fileStorageService, RedisTemplate<String, Object> redisTemplate) {
        super(fileStorageService, redisTemplate);
    }

}
