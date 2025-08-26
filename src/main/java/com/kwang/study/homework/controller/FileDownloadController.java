package com.kwang.study.homework.controller;

import com.kwang.study.common.R;
import com.kwang.study.fs.dto.result.FileObjectResult;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.utils.DownloadUtils;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.kwang.study.constant.ApiPrefixConstant.ATTACHE_DOWNLOAD_BASE_PREFIX;
import static com.kwang.study.constant.RedisKeyPrefixConstant.DOWNLOAD_ID_PREFIX;
import static com.kwang.study.homework.service.HomeworkService.HOMEWORK_FILE_PREFIX;

@RestController
@RequestMapping(ATTACHE_DOWNLOAD_BASE_PREFIX)
@Validated
public class FileDownloadController {
    @Autowired
    private FileStorageService fileStorageService;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @GetMapping("/get/downloadId")
    public ResponseEntity<R<String>> produceDownloadUUID(@NotBlank @RequestParam("path") String path) {
        String downloadId = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(DOWNLOAD_ID_PREFIX + downloadId, path, 30, TimeUnit.MINUTES);
        return ResponseEntity.ok(R.success(downloadId));
    }


    @GetMapping("/download")
    public void downloadFile(@NotBlank @RequestParam("path") String path,
                             @RequestParam(name = "mode", defaultValue = "attachment") String mode,
                             @NotBlank @RequestParam("token") String token,
                             HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tokenPath = (String) redisTemplate.opsForValue().get(DOWNLOAD_ID_PREFIX + token);
        if (!Objects.equals(path, tokenPath)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().print("没有权限");
            response.setContentType("text/plain; charset=UTF-8");
            return;
        }

        FileObjectResult fileObject = fileStorageService.getFileObject(HOMEWORK_FILE_PREFIX + path);

        DownloadUtils.downloadFile(fileObject, mode, request, response);
    }
}
