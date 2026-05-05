package com.kwang.study.homework.controller;

import com.kwang.study.common.R;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.utils.BaseFileDownloadController;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.constraints.NotBlank;
import java.io.IOException;

import static com.kwang.study.constant.ApiPrefixConstant.ATTACHE_DOWNLOAD_BASE_PREFIX;

@RestController
@RequestMapping(ATTACHE_DOWNLOAD_BASE_PREFIX)
@Validated
public class AttacheFileDownloadController extends BaseFileDownloadController {

    public AttacheFileDownloadController(FileStorageService fileStorageService, RedisTemplate<String, Object> redisTemplate) {
        super(fileStorageService, redisTemplate);
    }

    @GetMapping("/get/downloadId")
    public ResponseEntity<R<String>> produceDownloadUUID(@NotBlank @RequestParam("path") String path,
                                                         @NotBlank @RequestParam("fileName") String fileName) {
        return super.produceDownloadUUID(path, fileName);
    }


    @GetMapping("/download")
    public void downloadFile(@NotBlank @RequestParam("path") String path,
                             @RequestParam(name = "mode", defaultValue = "attachment") String mode,
                             @NotBlank @RequestParam("token") String token,
                             HttpServletRequest request, HttpServletResponse response) throws IOException {
        super.downloadFile(path, mode, token, request, response);
    }
}
