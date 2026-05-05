package com.kwang.study.homework.controller;

import com.kwang.study.common.R;
import com.kwang.study.fs.dto.result.GenericObjectResult;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.homework.dto.request.BatchUploadInitRequestDTO;
import com.kwang.study.homework.dto.result.UploadInitResult;
import com.kwang.study.homework.service.HomeworkService;
import com.kwang.study.utils.BaseFileUploadController;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.io.IOException;
import java.util.List;

import static com.kwang.study.constant.ApiPrefixConstant.ATTACHE_UPLOAD_BASE_PREFIX;

@RestController
@RequestMapping(ATTACHE_UPLOAD_BASE_PREFIX)
@Validated
public class AttacheFileUploadController extends BaseFileUploadController {

    protected AttacheFileUploadController(FileStorageService fileStorageService, RedisTemplate<String, Object> redisTemplate) {
        super(fileStorageService, redisTemplate);
    }

    /**
     * 批量初始化分块上传
     */
    @PostMapping("/batch-init")
    public ResponseEntity<R<List<UploadInitResult>>> batchInitChunkUpload(@Valid @RequestBody BatchUploadInitRequestDTO requestDTO)
            throws IOException {

        return super.batchInitChunkUpload(requestDTO);
    }

    /**
     * 上传文件块
     */
    @PostMapping("/chunk")
    public ResponseEntity<R<GenericObjectResult>> uploadChunk(@RequestParam("uploadId") String uploadId,
                                            @RequestParam("chunkIndex") Integer chunkIndex,
                                            @RequestParam("totalChunks") Integer totalChunks,
                                            @RequestPart("chunk") MultipartFile chunk) throws IOException {
        return super.uploadChunk(uploadId, chunkIndex, totalChunks, chunk);
    }

    /**
     * 合并文件块
     */
    @PostMapping("/merge")
    public ResponseEntity<R<GenericObjectResult>> mergeChunk(@RequestParam("uploadId") String uploadId,
                                                              @RequestParam("totalChunks") Integer totalChunks) throws IOException {
        return super.mergeChunk(uploadId, totalChunks);
    }

    @Override
    public String produceFilePath(String fileName) {
        return HomeworkService.produceAttachPath(fileName);
    }

    // TODO: 需要加一个终止上传操作
}
