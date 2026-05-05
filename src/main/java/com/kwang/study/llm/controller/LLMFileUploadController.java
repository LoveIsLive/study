package com.kwang.study.llm.controller;

import cn.hutool.core.lang.UUID;
import com.kwang.study.common.R;
import com.kwang.study.fs.dto.result.GenericObjectResult;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.homework.dto.request.BatchUploadInitRequestDTO;
import com.kwang.study.homework.dto.result.UploadInitResult;
import com.kwang.study.utils.BaseFileUploadController;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

import static com.kwang.study.constant.ApiPrefixConstant.LLM_BASE_PREFIX;
import static com.kwang.study.enums.FileStorageModuleNameEnum.LLMCHAT_NAME;

@RestController
@RequestMapping(LLM_BASE_PREFIX)
@Validated
public class LLMFileUploadController extends BaseFileUploadController {

    protected LLMFileUploadController(FileStorageService fileStorageService, RedisTemplate<String, Object> redisTemplate) {
        super(fileStorageService, redisTemplate);
    }

    /**
     * 批量初始化分块上传
     */
    @PostMapping("/batch-init")
    public ResponseEntity<R<List<UploadInitResult>>> batchInitChunkUpload(BatchUploadInitRequestDTO requestDTO)
            throws IOException {

        return super.batchInitChunkUpload(requestDTO);
    }

    /**
     * 上传文件块
     */
    @PostMapping("/chunk")
    public ResponseEntity<R<GenericObjectResult>> uploadChunk(String uploadId, Integer chunkIndex,
                                            Integer totalChunks, MultipartFile chunk) throws IOException {
        return super.uploadChunk(uploadId, chunkIndex, totalChunks, chunk);
    }

    /**
     * 合并文件块
     */
    @PostMapping("/merge")
    public ResponseEntity<R<GenericObjectResult>> mergeChunk(String uploadId, Integer totalChunks) throws IOException {
        return super.mergeChunk(uploadId, totalChunks);
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

    // TODO: 需要加一个终止上传操作
}
