package com.kwang.study.mathvision.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.transfer.MathVisionTransferClient;
import com.kwang.study.mathvision.workflow.model.RenderResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MathVisionFinalArtifactStorageServiceTest {
    @TempDir Path dir;
    @Test void uploadsThroughSameClientAndKeepsLocalFileUntilCommit() throws Exception {
        var client = mock(MathVisionTransferClient.class);
        var service = new MathVisionFinalArtifactStorageService(client);
        Path file = dir.resolve("final.mp4"); Files.writeString(file, "video");
        var task = MathVisionTask.builder().id(1L).currentVersion(2).lockToken("token").build();
        var render = new RenderResult(); render.setArtifactPath(file.toString());
        when(client.upload(eq(task), eq(file), eq("mp4"))).thenReturn(new ObjectMapper().createObjectNode()
                .put("path", "/mathvision/task-1/v2/runs/token/final.mp4").put("receipt", "signed"));
        var stored = service.store(task, render);
        assertTrue(Files.exists(file));
        assertEquals("signed", render.getArtifactReceipt());
        assertEquals("/mathvision/task-1/v2/runs/token/final.mp4", stored.getPath());
        verify(client).upload(task, file, "mp4");
    }
    @Test void retainsLocalFileWhenMasterIsUnavailable() throws Exception {
        var client = mock(MathVisionTransferClient.class);
        Path file = dir.resolve("final.html"); Files.writeString(file, "html");
        var render = new RenderResult(); render.setArtifactPath(file.toString());
        var task = MathVisionTask.builder().id(1L).build();
        when(client.upload(any(), any(), any())).thenThrow(new IOException("offline"));
        assertThrows(IOException.class, () -> new MathVisionFinalArtifactStorageService(client).store(task, render));
        assertTrue(Files.exists(file));
    }
}
