package com.kwang.study.homework.controller;

import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.homework.service.HomeworkService;
import com.kwang.study.utils.BaseFileUploadController;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static com.kwang.study.constant.ApiPrefixConstant.ATTACHE_UPLOAD_BASE_PREFIX;

@RestController
@RequestMapping(ATTACHE_UPLOAD_BASE_PREFIX)
@Validated
public class AttacheFileUploadController extends BaseFileUploadController {

    protected AttacheFileUploadController(FileStorageService fileStorageService, RedisTemplate<String, Object> redisTemplate) {
        super(fileStorageService, redisTemplate);
    }

    @Override
    public String produceFilePath(String fileName) {
        return HomeworkService.produceAttachPath(fileName);
    }

    // TODO: 需要加一个终止上传操作
}
