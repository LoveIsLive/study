package com.kwang.study.homework.controller;

import com.kwang.study.common.R;
import com.kwang.study.fs.dto.result.GenericObjectResult;
import com.kwang.study.fs.dto.result.InitMultiUploadResult;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.homework.dto.request.BatchUploadInitRequestDTO;
import com.kwang.study.homework.dto.request.FileMetaDTO;
import com.kwang.study.homework.dto.request.UploadInfoRedisDTO;
import com.kwang.study.homework.dto.result.UploadInitResult;
import com.kwang.study.homework.service.HomeworkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.kwang.study.constant.ApiPrefixConstant.ATTACHE_UPLOAD_BASE_PREFIX;
import static com.kwang.study.constant.RedisKeyPrefixConstant.UPLOAD_ID_PREFIX;

@RestController
@RequestMapping(ATTACHE_UPLOAD_BASE_PREFIX)
@Validated
public class FileUploadController {
    @Autowired
    private FileStorageService fileStorageService;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 批量初始化分块上传
     */
    @PostMapping("/batch-init")
    public ResponseEntity<R<List<UploadInitResult>>> batchInitChunkUpload(@Valid @RequestBody BatchUploadInitRequestDTO requestDTO)
            throws IOException {
        List<UploadInitResult> responseList = new ArrayList<>();

        for (FileMetaDTO fileMeta : requestDTO.getFiles()) {
            String filePath = HomeworkService.produceAttachPath(fileMeta.getFileName());

            // 2. 初始化文件存储服务
            InitMultiUploadResult result = fileStorageService.initMultiUpload(filePath, fileMeta.getMimeTypeName());
            String uploadId = result.getUploadId();

            redisTemplate.opsForValue().set(UPLOAD_ID_PREFIX + uploadId, UploadInfoRedisDTO.builder()
                    .uploaderId(uploadId)
                    .fileSize(fileMeta.getFileSize())
                    .mimeTypeName(fileMeta.getMimeTypeName())
                    .filePath(filePath)
                    .fileName(fileMeta.getFileName())
                    .build()
                    , 60, TimeUnit.MINUTES);

            // 4. 准备返回给前端的信息
            responseList.add(new UploadInitResult(fileMeta.getFileName(), uploadId));
        }

        return ResponseEntity.ok(R.success(responseList));
    }

    /**
     * 上传文件块
     */
    @PostMapping("/chunk")
    public ResponseEntity<R<GenericObjectResult>> uploadChunk(@RequestParam("uploadId") String uploadId,
                                            @RequestParam("chunkIndex") Integer chunkIndex,
                                            @RequestParam("totalChunks") Integer totalChunks,
                                            @RequestPart("chunk") MultipartFile chunk) throws IOException {
        try (InputStream input = chunk.getInputStream()) {
            GenericObjectResult result = fileStorageService.
                    uploadChunk(uploadId, chunkIndex, totalChunks, input);
            return ResponseEntity.ok(R.success(result));
        }
    }

    /**
     * 合并文件块
     */
    @PostMapping("/merge")
    public ResponseEntity<R<GenericObjectResult>> mergeChunk(@RequestParam("uploadId") String uploadId,
                                                              @RequestParam("totalChunks") Integer totalChunks) throws IOException {
        GenericObjectResult result = fileStorageService.
                mergeChunk(uploadId, totalChunks);
        return ResponseEntity.ok(R.success(result));
    }
}
