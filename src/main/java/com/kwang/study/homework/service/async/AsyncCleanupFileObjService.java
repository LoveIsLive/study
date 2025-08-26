package com.kwang.study.homework.service.async;

import com.kwang.study.fs.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.util.List;

@Service
@Slf4j
public class AsyncCleanupFileObjService {

    @Autowired
    private FileStorageService fsService;

    @Async
    public void cleanup(List<String> keys) {
        if (CollectionUtils.isEmpty(keys)) return;
        try {
            // 删除物理分片文件
            for (String k : keys) {
                try {
                    fsService.deleteFileObject(k);
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
