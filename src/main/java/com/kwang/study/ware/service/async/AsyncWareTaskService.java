package com.kwang.study.ware.service.async;

import com.kwang.study.fs.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class AsyncWareTaskService {

    @Autowired
    private FileStorageService fsService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Async
    public void executeArchiveTask(String actualSourcePath, String actualZipPath, String username,
                                   String sourceDirPath, String zipFileName) {
        String destination = "/queue/task-notifications";
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "FILE_TASK");
        payload.put("taskType", "ARCHIVE");
        // 原封不动回传给前端的参数
        payload.put("sourceDirPath", sourceDirPath);
        payload.put("zipFileName", zipFileName);

        try {
            log.info("用户 [{}] 提交的打包任务开始执行: {}", username, actualSourcePath);
            fsService.archiveDirectory(actualSourcePath, actualZipPath);

            payload.put("status", "SUCCESS");
            payload.put("message", "打包完成: " + zipFileName);
            log.info("打包任务完成: {}", actualZipPath);
        } catch (Exception e) {
            log.error("打包任务失败", e);
            payload.put("status", "ERROR");
            payload.put("message", "打包失败: " + e.getMessage());
        }
        messagingTemplate.convertAndSendToUser(username, destination, payload);
    }

    @Async
    public void executeUnarchiveTask(String actualZipPath, String actualTargetDirPath, String username,
                                     String zipFilePath, String targetDirPath) {
        String destination = "/queue/task-notifications";
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "FILE_TASK");
        payload.put("taskType", "UNARCHIVE");
        // 原封不动回传给前端的参数
        payload.put("zipFilePath", zipFilePath);
        payload.put("targetDirPath", targetDirPath);

        try {
            log.info("用户 [{}] 提交的解压任务开始执行: {}", username, actualZipPath);
            fsService.unarchiveFile(actualZipPath, actualTargetDirPath);

            payload.put("status", "SUCCESS");
            payload.put("message", "解压完成");
            log.info("解压任务完成: {}", actualTargetDirPath);
        } catch (Exception e) {
            log.error("解压任务失败", e);
            payload.put("status", "ERROR");
            payload.put("message", "解压失败: " + e.getMessage());
        }
        messagingTemplate.convertAndSendToUser(username, destination, payload);
    }
}