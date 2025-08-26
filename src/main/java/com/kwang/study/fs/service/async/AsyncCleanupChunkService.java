package com.kwang.study.fs.service.async;

import com.kwang.study.fs.storage.FileStorage;
import com.kwang.study.fs.mapper.FileChunkMapper;
import com.kwang.study.fs.pojo.FileChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class AsyncCleanupChunkService {
    @Autowired
    @Qualifier("chunkStorage")
    private FileStorage chunkStorage;

    @Autowired
    private FileChunkMapper fileChunkMapper;

    @Async
    public void cleanup(List<FileChunk> chunks) {
        if (CollectionUtils.isEmpty(chunks)) return;
        Long fileId = chunks.get(0).getFileId();
        try {
            // 删除物理分片文件
            for (FileChunk chunk : chunks) {
                try {
                    chunkStorage.deleteFile(chunk.getKey());
                } catch (IOException e) {
                    log.error("Failed to delete chunk file: {}", chunk.getKey(), e);
                }
            }
            // 删除数据库分片记录
            fileChunkMapper.deleteByFileId(fileId);
            log.info("Successfully cleaned up chunks for fileId: {}", fileId);
        } catch (Exception e) {
            log.error("Error during async chunk cleanup for fileId: {}", fileId, e);
        }
    }
}
