package com.kwang.study.controller.fs;

import com.kwang.study.common.R;
import com.kwang.study.dto.fs.request.InitUploadBigFileRequestDTO;
import com.kwang.study.dto.fs.request.UploadChunkRequestDTO;
import com.kwang.study.pojo.fs.Node;
import com.kwang.study.service.fs.ChunkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;

import static com.kwang.study.constant.ApiPrefixConstant.FS_BASE_PREFIX;

@RestController
@RequestMapping(FS_BASE_PREFIX + "/chunk")
@Validated
@Slf4j
public class ChunkController {
    @Autowired
    private ChunkService chunkService;

    // 使用简单的本地锁来防止并发合并问题
    private final ConcurrentHashMap<Long, Object> mapLock = new ConcurrentHashMap<>();

    @PostMapping("/init")
    public ResponseEntity<R<Node>> initUploadBigFile(@Valid @RequestBody InitUploadBigFileRequestDTO requestDTO) {
        requestDTO.check();

        Node node = chunkService.initBigFileNode(requestDTO.getName(), requestDTO.getParentId(),
                requestDTO.getPermissions(), requestDTO.getMimeTypeName());
        return ResponseEntity.ok(R.success(node, "大文件初始化成功"));
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadChunk(@Valid @RequestBody UploadChunkRequestDTO requestDTO) throws Exception {
        requestDTO.check();

        long fileId = requestDTO.getFileId();
        int totalChunks = requestDTO.getTotalChunks();

        try (InputStream chunkInputStream = requestDTO.getChunk().getInputStream()) {
            chunkService.uploadChunk(fileId, requestDTO.getChunkIndex(), chunkInputStream);
        }
        int uploadedCount = chunkService.countUploadedChunks(fileId);


        // 双重检查锁定，防止高并发下重复调用mergeChunks
        if (uploadedCount == totalChunks) {
            Object val = mapLock.putIfAbsent(fileId, new Object());
            if (val != null) {
                log.info("并发合并，fileId: {}", fileId);
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(R.success( "正在合并"));
            }
            try {
                // 再次检查，因为可能在获取锁的期间，其他线程已经完成了合并
                uploadedCount = chunkService.countUploadedChunks(fileId);
                if (uploadedCount == totalChunks) {
                    log.info("All chunks for fileId {} are uploaded. Starting merge.", fileId);
                    chunkService.mergeChunks(fileId);
                    return ResponseEntity.ok(R.success("Upload complete and file merged."));
                }
            } finally {
                mapLock.remove(fileId);
            }
        }

        String progress = String.format("Chunk %d/%d uploaded.", uploadedCount, totalChunks);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(R.success(progress, "分片上传成功"));
    }

}
