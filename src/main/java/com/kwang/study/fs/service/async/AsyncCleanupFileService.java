package com.kwang.study.fs.service.async;

import com.kwang.study.fs.storage.FileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.util.List;

@Service
@Slf4j
public class AsyncCleanupFileService {
    @Autowired
    @Qualifier("fileStorage")
    private FileStorage fileStorage;

    @Async
    public void cleanup(List<String> keys) {
        if (CollectionUtils.isEmpty(keys)) return;
        try {
            // 删除物理分片文件
            for (String k : keys) {
                try {
                    fileStorage.deleteFile(k);
                } catch (IOException e) {
                    log.error("Failed to delete file: {}", k, e);
                }
            }
            log.info("Success during async file cleanup: {}", keys);
        } catch (Exception e) {
            log.error("Error during async file cleanup: {}", keys, e);
        }
    }
}
