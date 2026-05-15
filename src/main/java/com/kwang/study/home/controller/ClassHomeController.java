package com.kwang.study.home.controller;

import com.kwang.study.common.R;
import com.kwang.study.fs.dto.result.FileObjectResult;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.home.dto.request.UpdateClassHomeDTO;
import com.kwang.study.home.pojo.ClassHome;
import com.kwang.study.home.service.ClassHomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/class-home")
@RequiredArgsConstructor
@Validated
public class ClassHomeController {

    private final ClassHomeService classHomeService;
    private final FileStorageService fileStorageService;

    /**
     * 获取班级主页详情 (所有人可看)
     */
    @GetMapping("/detail/{classId}")
    public R<ClassHome> getDetail(@PathVariable Long classId) {
        return R.success(classHomeService.getClassHomeDetail(classId));
    }

    /**
     * 更新/创建班级主页 (教师及以上权限可看)
     * 采用 Multipart 形式接收 DTO 和封面文件
     */
    @PostMapping("/update")
    public R<ClassHome> updateClassHome(
            @Valid @RequestPart("dto") UpdateClassHomeDTO dto,
            @RequestPart(value = "coverImage", required = false) MultipartFile coverImage) {

        return R.success(classHomeService.updateClassHome(dto, coverImage));
    }

    /**
     * 加载封面图流
     */
    @GetMapping("/getCoverImage")
    public ResponseEntity<StreamingResponseBody> getCoverImage(@NotBlank @RequestParam("path") String path) throws IOException {
        FileObjectResult fileObject = fileStorageService.getFileObject(path);

        if (fileObject == null || fileObject.getContent() == null) {
            return ResponseEntity.notFound().build();
        }

        StreamingResponseBody responseBody = outputStream -> {
            try (InputStream inputStream = fileObject.getContent()) {
                StreamUtils.copy(inputStream, outputStream);
            } catch (IOException ignored) {}
        };

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(fileObject.getMimeTypeName());
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(fileObject.getSize())
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic())
                .body(responseBody);
    }
}