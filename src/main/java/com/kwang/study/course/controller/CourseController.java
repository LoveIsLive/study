package com.kwang.study.course.controller;

import com.kwang.study.common.R;
import com.kwang.study.course.dto.request.CourseDTO;
import com.kwang.study.course.pojo.Course;
import com.kwang.study.course.service.CourseService;
import com.kwang.study.fs.dto.result.FileObjectResult;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.homework.dto.result.DownloadDTO;
import com.kwang.study.utils.DownloadUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
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
import java.util.List;
import java.util.concurrent.TimeUnit;


@RestController
@RequestMapping("/api/v1/course")
@RequiredArgsConstructor
@Validated
public class CourseController {

    private final CourseService courseService;
    private final FileStorageService fileStorageService;

    @PostMapping("/create")
    public R<Course> createCourse(@Valid @RequestPart("dto") CourseDTO dto,
                                  @RequestPart(value = "coverImage", required = false) MultipartFile coverImage) {
        return R.success(courseService.createCourse(dto, coverImage));
    }

    @PutMapping("/{courseId}")
    public R<Course> updateCourse(@PathVariable Long courseId, @Valid @RequestPart("dto") CourseDTO dto,
                                  @RequestPart(value = "coverImage", required = false) MultipartFile coverImage) {
        return R.success(courseService.updateCourse(courseId, dto, coverImage));
    }

    @DeleteMapping("/{courseId}")
    public R<Void> deleteCourse(@PathVariable Long courseId) {
        courseService.deleteCourse(courseId);
        return R.success(null, "课程删除成功（关联资源予以保留）");
    }

    @GetMapping("/{courseId}")
    public R<Course> getCourse(@PathVariable Long courseId) {
        return R.success(courseService.getCourseById(courseId));
    }

    @GetMapping("/class/{classId}")
    public R<List<Course>> getCoursesByClassId(@PathVariable Long classId) {
        return R.success(courseService.getCoursesByClassId(classId));
    }

    @GetMapping("/getCoverImage")
    public ResponseEntity<StreamingResponseBody> getCoverImage(@NotBlank @RequestParam("path") String path) throws IOException {
        // 简单获取封面图片，没有做权限校验
        FileObjectResult fileObject = fileStorageService.getFileObject(path);

        if (fileObject == null || fileObject.getContent() == null) {
            return ResponseEntity.notFound().build();
        }

        // 定义流式返回的 body
        StreamingResponseBody responseBody = outputStream -> {
            // 在实际发生数据写入时，通过 try-with-resources 安全关闭 InputStream
            try (InputStream inputStream = fileObject.getContent()) {
                StreamUtils.copy(inputStream, outputStream);
            } catch (IOException e) {
                // 忽略或记录客户端断开异常
            }
        };

        // 解析 MediaType 并提供兜底方案（防止 MediaType.valueOf 抛出 InvalidMediaTypeException 导致流未消费）
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