package com.kwang.study.mathvision.service;

import com.kwang.study.fs.config.FSConfig;
import com.kwang.study.fs.dto.result.DirObjectResult;
import com.kwang.study.fs.dto.result.InitMultiUploadResult;
import com.kwang.study.fs.dto.result.MimeTypeIdResult;
import com.kwang.study.fs.enums.ObjectTypeEnum;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.workflow.model.RenderResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MathVisionFinalArtifactStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void storesSmallHtmlArtifactWithPlatformFileStoragePath() throws Exception {
        FileStorageService fileStorageService = mock(FileStorageService.class);
        when(fileStorageService.getMimeTypeId(anyString())).thenReturn(supportedMime());
        when(fileStorageService.getDirectoryObject("/mathvision/task-7/v3/final"))
                .thenReturn(directoryWithFiles("artifact-old.html", "artifact-stale.mp4"));
        FSConfig fsConfig = new FSConfig();
        fsConfig.setChunkSize(1024);
        MathVisionFinalArtifactStorageService service =
                new MathVisionFinalArtifactStorageService(fileStorageService, fsConfig);

        Path html = tempDir.resolve("preview.html");
        Files.writeString(html, "<html></html>", StandardCharsets.UTF_8);

        MathVisionFinalArtifactStorageService.StoredArtifact stored = service.store(
                task(7L, 3), renderResult(html, "html"));

        assertEquals("/mathvision/task-7/v3/final/final.html", stored.getPath());
        assertFalse(Files.exists(html));
        verify(fileStorageService).createFile(
                org.mockito.ArgumentMatchers.startsWith("/mathvision/task-7/v3/final/upload-"),
                any(InputStream.class), eq("text/html"));
        verify(fileStorageService).deleteFileObject("/mathvision/task-7/v3/final/artifact-old.html");
        verify(fileStorageService).deleteFileObject("/mathvision/task-7/v3/final/artifact-stale.mp4");
        verify(fileStorageService).updateFileObject(
                org.mockito.ArgumentMatchers.startsWith("/mathvision/task-7/v3/final/upload-"),
                eq("final.html"), isNull(), isNull());
        verify(fileStorageService, never()).initMultiUpload(anyString(), anyString());
    }

    @Test
    void storesLargeMp4ArtifactWithChunkUpload() throws Exception {
        FileStorageService fileStorageService = mock(FileStorageService.class);
        when(fileStorageService.getMimeTypeId(anyString())).thenReturn(supportedMime());
        InitMultiUploadResult init = new InitMultiUploadResult();
        init.setSuccess(Boolean.TRUE);
        init.setUploadId("upload-1");
        when(fileStorageService.initMultiUpload(anyString(), eq("video/mp4"))).thenReturn(init);
        FSConfig fsConfig = new FSConfig();
        fsConfig.setChunkSize(5);
        MathVisionFinalArtifactStorageService service =
                new MathVisionFinalArtifactStorageService(fileStorageService, fsConfig);

        Path video = tempDir.resolve("scene.mp4");
        Files.writeString(video, "abcdefghijkl", StandardCharsets.UTF_8);

        MathVisionFinalArtifactStorageService.StoredArtifact stored = service.store(
                task(8L, 2), renderResult(video, "mp4"));

        assertEquals("/mathvision/task-8/v2/final/final.mp4", stored.getPath());
        assertFalse(Files.exists(video));
        verify(fileStorageService, never()).createFile(anyString(), any(InputStream.class), anyString());
        verify(fileStorageService).initMultiUpload(
                org.mockito.ArgumentMatchers.startsWith("/mathvision/task-8/v2/final/upload-"),
                eq("video/mp4"));
        verify(fileStorageService, times(3))
                .uploadChunk(eq("upload-1"), anyInt(), eq(3), any(InputStream.class));
        verify(fileStorageService).mergeChunk("upload-1", 3);
        verify(fileStorageService).updateFileObject(
                org.mockito.ArgumentMatchers.startsWith("/mathvision/task-8/v2/final/upload-"),
                eq("final.mp4"), isNull(), isNull());
    }

    private static MathVisionTask task(Long id, Integer version) {
        return MathVisionTask.builder()
                .id(id)
                .currentVersion(version)
                .build();
    }

    private static RenderResult renderResult(Path path, String artifactType) {
        RenderResult result = new RenderResult();
        result.setArtifactPath(path.toString());
        result.setArtifactType(artifactType);
        return result;
    }

    private static MimeTypeIdResult supportedMime() {
        MimeTypeIdResult result = new MimeTypeIdResult();
        result.setSuccess(Boolean.TRUE);
        result.setMimeTypeId(1);
        return result;
    }

    private static DirObjectResult directoryWithFiles(String... names) {
        DirObjectResult result = new DirObjectResult();
        List<DirObjectResult.FileObjectDesc> entries = new ArrayList<>();
        for (String name : names) {
            DirObjectResult.FileObjectDesc entry = new DirObjectResult.FileObjectDesc();
            entry.setName(name);
            entry.setType(ObjectTypeEnum.FILE.getCode());
            entries.add(entry);
        }
        result.setFileObjectDescs(entries);
        return result;
    }
}
