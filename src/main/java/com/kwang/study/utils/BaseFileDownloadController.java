package com.kwang.study.utils;

import com.kwang.study.common.R;
import com.kwang.study.fs.dto.result.FileObjectResult;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.homework.dto.result.DownloadDTO;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.kwang.study.constant.RedisKeyPrefixConstant.DOWNLOAD_ID_PREFIX;

public abstract class BaseFileDownloadController {
    private final FileStorageService fileStorageService;
    private final RedisTemplate<String, Object> redisTemplate;

    public BaseFileDownloadController(FileStorageService fileStorageService, RedisTemplate<String, Object> redisTemplate) {
        this.fileStorageService = fileStorageService;
        this.redisTemplate = redisTemplate;
    }

    public ResponseEntity<R<String>> produceDownloadUUID(String path, String fileName) {
        String downloadId = UUID.randomUUID().toString();
        DownloadDTO dto = new DownloadDTO(path, fileName);
        redisTemplate.opsForValue().set(DOWNLOAD_ID_PREFIX + downloadId, dto, 30, TimeUnit.MINUTES);
        return ResponseEntity.ok(R.success(downloadId));
    }


    public void downloadFile(String path, String mode, String token,
                             HttpServletRequest request, HttpServletResponse response) throws IOException {
        DownloadDTO dto = (DownloadDTO) redisTemplate.opsForValue().get(DOWNLOAD_ID_PREFIX + token);
        if (dto == null || !Objects.equals(path, dto.getActualPath())) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().print("没有权限");
            response.setContentType("text/plain; charset=UTF-8");
            return;
        }

        FileObjectResult fileObject = fileStorageService.getFileObject(path);
        fileObject.setName(dto.getFileName());
        DownloadUtils.downloadFile(fileObject, mode, request, response);
    }
}
