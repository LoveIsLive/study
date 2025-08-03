package com.kwang.study.controller.fs;

import com.kwang.study.common.R;
import com.kwang.study.pojo.Node;
import com.kwang.study.service.ChunkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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

    // 使用简单的本地锁来防止并发合并问题，生产环境建议使用分布式锁（如Redis）
    private final ConcurrentHashMap<Long, Object> mapLock = new ConcurrentHashMap<>();

    @PostMapping("/init")
    public ResponseEntity<R<Node>> initUploadBigFile(
            @RequestParam("name") @NotBlank(message = "文件名不能为空") @Pattern(regexp = "^[^/\\\\:*?\"<>|]+$", message = "文件名不能包含非法字符") String name,
            @RequestParam("parentId") Long parentId,
            @RequestParam(value = "permissions", required = false)
            @Pattern(regexp = "^([r-][w-][x-]){3}$",
                    message = "文件权限格式不正确，应为rwxrwxrwx形式") String permissions,
            @RequestParam(value = "mimeTypeName") @NotBlank(message = "MIME类型不能为空") String mimeTypeName) {

        Node node = chunkService.initBigFileNode(name, parentId, permissions, mimeTypeName);
        return ResponseEntity.ok(R.success(node, "大文件初始化成功"));
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadChunk(
            @RequestParam("fileId") @NotNull Long fileId,
            @RequestParam("chunkIndex") @Min(0) Integer chunkIndex,
            @RequestParam("totalChunks") @Min(1) Integer totalChunks,
            @RequestParam("chunk") @NotNull MultipartFile chunk) throws Exception {

        try (InputStream chunkInputStream = chunk.getInputStream()) {
            chunkService.uploadChunk(fileId, chunkIndex, chunkInputStream);
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
