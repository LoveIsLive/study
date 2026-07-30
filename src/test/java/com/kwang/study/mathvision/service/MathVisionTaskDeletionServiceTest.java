package com.kwang.study.mathvision.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.auth.custom.CustomUserDetails;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.llm.mapper.ChatMemoryMapper;
import com.kwang.study.llm.mapper.ChatSessionMapper;
import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.controller.MathVisionFileUploadController;
import com.kwang.study.mathvision.mapper.LlmModelConfigMapper;
import com.kwang.study.mathvision.mapper.MathVisionArtifactMapper;
import com.kwang.study.mathvision.mapper.MathVisionStageResultMapper;
import com.kwang.study.mathvision.mapper.MathVisionTaskMapper;
import com.kwang.study.mathvision.mapper.MathVisionVersionMapper;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MathVisionTaskDeletionServiceTest {

    private static final Long USER_ID = 7L;

    @Mock private ChatSessionMapper chatSessionMapper;
    @Mock private ChatMemoryMapper chatMemoryMapper;
    @Mock private MathVisionTaskMapper taskMapper;
    @Mock private MathVisionVersionMapper versionMapper;
    @Mock private MathVisionArtifactMapper artifactMapper;
    @Mock private MathVisionStageResultMapper stageResultMapper;
    @Mock private LlmModelConfigMapper configMapper;
    @Mock private MathVisionModelCatalog catalog;
    @Mock private FileStorageService fileStorageService;
    @Mock private MathVisionFileUploadController uploadController;
    @Mock private MathVisionTaskNotifier taskNotifier;

    @TempDir Path renderOutputRoot;

    private MathVisionTaskService service;

    @BeforeEach
    void setUp() {
        CustomUserDetails principal = CustomUserDetails.builder()
                .id(USER_ID)
                .username("mathvision-user")
                .authorities(Collections.emptyList())
                .enabled(true)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        service = new MathVisionTaskService(
                chatSessionMapper,
                chatMemoryMapper,
                taskMapper,
                versionMapper,
                artifactMapper,
                stageResultMapper,
                configMapper,
                catalog,
                fileStorageService,
                uploadController,
                taskNotifier,
                new ObjectMapper(),
                renderOutputRoot.toString());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void movesIdleTaskToRecycleBin() {
        MathVisionTask task = task(11L, "completed");
        when(taskMapper.findById(11L)).thenReturn(task);
        when(taskMapper.softDelete(11L, USER_ID)).thenReturn(1);

        service.deleteTask(11L);

        verify(taskMapper).softDelete(11L, USER_ID);
    }

    @Test
    void requiresRunningTaskToBeCanceledBeforeDeletion() {
        when(taskMapper.findById(12L)).thenReturn(task(12L, "running"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.deleteTask(12L));

        assertEquals("任务正在执行，请先取消任务再删除", error.getMessage());
        verify(taskMapper, never()).softDelete(12L, USER_ID);
    }

    @Test
    void restoresDeletedTaskWithItsPreviousState() {
        MathVisionTask deleted = task(13L, "failed");
        when(taskMapper.findDeletedById(13L, USER_ID)).thenReturn(deleted);
        when(taskMapper.restore(13L, USER_ID)).thenReturn(1);
        when(taskMapper.findById(13L)).thenReturn(deleted);

        assertEquals("failed", service.restoreTask(13L).getStatus());

        verify(taskMapper).restore(13L, USER_ID);
        verify(taskNotifier).notifyTaskChanged(13L, "restored");
    }

    @Test
    void permanentlyDeletesTaskDataAndOwnedStorage() throws Exception {
        String ownedInput = "/mathvision/123e4567-e89b-12d3-a456-426614174000.png";
        MathVisionTask deleted = task(14L, "completed");
        deleted.setInputAssetsJson("[{\"filePath\":\"" + ownedInput + "\"},"
                + "{\"filePath\":\"/shared/keep.png\"}]");
        when(taskMapper.findDeletedById(14L, USER_ID)).thenReturn(deleted);
        when(taskMapper.hardDelete(14L, USER_ID)).thenReturn(1);
        Path localRenderFile = renderOutputRoot.resolve("task-14/v1/render/output.txt");
        Files.createDirectories(localRenderFile.getParent());
        Files.writeString(localRenderFile, "render output");

        service.permanentlyDeleteTask(14L);

        verify(stageResultMapper).deleteByTaskId(14L);
        verify(artifactMapper).deleteByTaskId(14L);
        verify(versionMapper).deleteByTaskId(14L);
        verify(taskMapper).hardDelete(14L, USER_ID);
        verify(chatMemoryMapper).deleteBySessionId("session-14");
        verify(chatSessionMapper).deleteBySessionId("session-14");
        verify(fileStorageService).deleteFileObject(ownedInput);
        verify(fileStorageService, never()).deleteFileObject("/shared/keep.png");
        verify(fileStorageService).deleteDirObject("/mathvision/task-14");
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(renderOutputRoot.resolve("task-14")));
    }

    private MathVisionTask task(Long id, String status) {
        return MathVisionTask.builder()
                .id(id)
                .userId(USER_ID)
                .sessionId("session-" + id)
                .status(status)
                .currentStage("completed".equals(status) ? "completed" : "problem_normalization")
                .currentVersion(1)
                .inputAssetsJson("[]")
                .build();
    }
}
