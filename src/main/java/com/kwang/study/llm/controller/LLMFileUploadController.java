package com.kwang.study.llm.controller;

import cn.hutool.core.lang.UUID;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.utils.BaseFileUploadController;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static com.kwang.study.constant.ApiPrefixConstant.LLM_BASE_PREFIX;
import static com.kwang.study.enums.FileStorageModuleNameEnum.LLMCHAT_NAME;

@RestController
@RequestMapping(LLM_BASE_PREFIX)
@Validated
public class LLMFileUploadController extends BaseFileUploadController {

    protected LLMFileUploadController(FileStorageService fileStorageService, RedisTemplate<String, Object> redisTemplate) {
        super(fileStorageService, redisTemplate);
    }

    @Override
    public String produceFilePath(String fileName) {
        String fileExtension = "";
        if (fileName != null && fileName.contains(".")) {
            fileExtension = fileName.substring(fileName.lastIndexOf("."));
        }
        String uniqueFileName = UUID.randomUUID().toString(true) + fileExtension;
        return LLMCHAT_NAME.getModuleName() + "/" + uniqueFileName;
    }

}
