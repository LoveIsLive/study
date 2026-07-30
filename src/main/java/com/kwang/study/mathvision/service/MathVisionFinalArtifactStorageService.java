package com.kwang.study.mathvision.service;

import com.kwang.study.enums.FileStorageModuleNameEnum;
import com.kwang.study.fs.config.FSConfig;
import com.kwang.study.fs.dto.result.DirObjectResult;
import com.kwang.study.fs.dto.result.InitMultiUploadResult;
import com.kwang.study.fs.dto.result.MimeTypeIdResult;
import com.kwang.study.fs.enums.ObjectTypeEnum;
import com.kwang.study.fs.exception.PathAlreadyExistsException;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.workflow.model.RenderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

@Service
public class MathVisionFinalArtifactStorageService {

    private static final Logger log = LoggerFactory.getLogger(MathVisionFinalArtifactStorageService.class);
    private static final int DEFAULT_CHUNK_SIZE = 10 * 1024 * 1024;
    private static final int STREAM_BUFFER_SIZE = 8192;

    private final FileStorageService fileStorageService;
    private final int chunkSize;

    public MathVisionFinalArtifactStorageService(FileStorageService fileStorageService,
                                                 FSConfig fsConfig) {
        this.fileStorageService = fileStorageService;
        this.chunkSize = fsConfig != null && fsConfig.getChunkSize() != null && fsConfig.getChunkSize() > 0
                ? fsConfig.getChunkSize()
                : DEFAULT_CHUNK_SIZE;
    }

    public StoredArtifact store(MathVisionTask task, RenderResult renderResult) throws IOException {
        if (task == null || task.getId() == null) {
            throw new IllegalArgumentException("MathVision task is missing");
        }
        if (renderResult == null || !StringUtils.hasText(renderResult.getArtifactPath())) {
            throw new IllegalArgumentException("Render artifact path is missing");
        }
        Path source = Path.of(renderResult.getArtifactPath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Render artifact file does not exist: " + source);
        }

        String artifactType = normalizeArtifactType(renderResult.getArtifactType(), source);
        String extension = extensionFor(artifactType, source);
        String mimeType = requireSupportedMimeType(mimeTypeFor(artifactType, extension));
        String fileName = "final" + extension;
        String storageDirectory = FileStorageModuleNameEnum.MATHVISION_NAME.getModuleName()
                + "/task-" + task.getId()
                + "/v" + (task.getCurrentVersion() == null ? 1 : task.getCurrentVersion())
                + "/final";
        String storagePath = storageDirectory + "/" + fileName;
        String uploadFileName = "upload-" + UUID.randomUUID() + extension;
        String uploadPath = storageDirectory + "/" + uploadFileName;
        long sourceSize = Files.size(source);

        ensureParentDirectories(storagePath);
        uploadFile(uploadPath, source, mimeType);
        promoteUploadedArtifact(storageDirectory, uploadPath, uploadFileName, fileName);
        deleteLocalArtifact(source, storagePath);
        return new StoredArtifact(storagePath, fileName, artifactType, mimeType, sourceSize);
    }

    private void promoteUploadedArtifact(String storageDirectory,
                                         String uploadPath,
                                         String uploadFileName,
                                         String finalFileName) throws IOException {
        try {
            DirObjectResult directory = fileStorageService.getDirectoryObject(storageDirectory);
            if (directory != null && directory.getFileObjectDescs() != null) {
                for (DirObjectResult.FileObjectDesc entry : directory.getFileObjectDescs()) {
                    if (entry == null
                            || !ObjectTypeEnum.FILE.getCode().equals(entry.getType())
                            || uploadFileName.equals(entry.getName())) {
                        continue;
                    }
                    fileStorageService.deleteFileObject(storageDirectory + "/" + entry.getName());
                }
            }
            fileStorageService.updateFileObject(uploadPath, finalFileName, null, null);
        } catch (Exception e) {
            deletePlatformArtifactQuietly(uploadPath);
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Failed to promote final MathVision artifact: " + e.getMessage(), e);
        }
    }

    private void deletePlatformArtifactQuietly(String path) {
        try {
            fileStorageService.deleteFileObject(path);
        } catch (Exception cleanupError) {
            log.warn("MathVision temporary FS artifact cleanup failed, path={}, error={}",
                    path, cleanupError.getMessage());
        }
    }

    private void uploadFile(String storagePath, Path source, String mimeType) throws IOException {
        long size = Files.size(source);
        if (size <= chunkSize) {
            try (InputStream input = Files.newInputStream(source)) {
                fileStorageService.createFile(storagePath, input, mimeType);
            }
            return;
        }

        int totalChunks = (int) ((size + chunkSize - 1L) / chunkSize);
        InitMultiUploadResult init = fileStorageService.initMultiUpload(storagePath, mimeType);
        if (init == null || !StringUtils.hasText(init.getUploadId())) {
            throw new IllegalStateException("File storage did not return an upload id");
        }

        try (InputStream input = Files.newInputStream(source)) {
            for (int index = 0; index < totalChunks; index++) {
                long remaining = Math.min(chunkSize, size - ((long) index * chunkSize));
                byte[] chunk = readChunk(input, remaining);
                fileStorageService.uploadChunk(
                        init.getUploadId(), index, totalChunks, new ByteArrayInputStream(chunk));
            }
        }
        fileStorageService.mergeChunk(init.getUploadId(), totalChunks);
    }

    private void deleteLocalArtifact(Path source, String storagePath) {
        try {
            boolean deleted = Files.deleteIfExists(source);
            if (deleted) {
                log.info("MathVision local render artifact deleted after FS archive, localPath={}, storagePath={}",
                        source, storagePath);
            }
        } catch (IOException e) {
            log.warn("MathVision local render artifact cleanup failed after FS archive, localPath={}, storagePath={}, error={}",
                    source, storagePath, e.getMessage(), e);
        }
    }

    private byte[] readChunk(InputStream input, long targetSize) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) targetSize);
        byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        long remaining = targetSize;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) {
                break;
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
        return output.toByteArray();
    }

    private void ensureParentDirectories(String storagePath) throws IOException {
        int lastSlash = storagePath.lastIndexOf('/');
        if (lastSlash <= 0) {
            return;
        }
        String directoryPath = storagePath.substring(0, lastSlash);
        String[] parts = directoryPath.substring(1).split("/");
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            current.append('/').append(part);
            try {
                fileStorageService.createDirectory(current.toString());
            } catch (PathAlreadyExistsException ignored) {
                // Directory already exists in the platform file system.
            }
        }
    }

    private String requireSupportedMimeType(String mimeType) {
        MimeTypeIdResult result = fileStorageService.getMimeTypeId(mimeType);
        if (result != null && result.getMimeTypeId() != null) {
            return mimeType;
        }
        if ("video/mp4".equals(mimeType)) {
            MimeTypeIdResult fallback = fileStorageService.getMimeTypeId("application/octet-stream");
            if (fallback != null && fallback.getMimeTypeId() != null) {
                return "application/octet-stream";
            }
        }
        throw new IllegalStateException("File storage mime type is not configured: " + mimeType);
    }

    private String normalizeArtifactType(String artifactType, Path source) {
        if (StringUtils.hasText(artifactType)) {
            return artifactType.trim().toLowerCase(Locale.ROOT);
        }
        String fileName = source.getFileName() == null ? "" : source.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && dot < fileName.length() - 1
                ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT)
                : "bin";
    }

    private String extensionFor(String artifactType, Path source) {
        if ("mp4".equals(artifactType) || "video".equals(artifactType)) {
            return ".mp4";
        }
        if ("html".equals(artifactType) || "geogebra".equals(artifactType)) {
            return ".html";
        }
        String fileName = source.getFileName() == null ? "" : source.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            return fileName.substring(dot).toLowerCase(Locale.ROOT);
        }
        return ".bin";
    }

    private String mimeTypeFor(String artifactType, String extension) {
        if ("mp4".equals(artifactType) || ".mp4".equals(extension)) {
            return "video/mp4";
        }
        if ("html".equals(artifactType) || "geogebra".equals(artifactType) || ".html".equals(extension)) {
            return "text/html";
        }
        return "application/octet-stream";
    }

    public static final class StoredArtifact {
        private final String path;
        private final String fileName;
        private final String artifactType;
        private final String mimeType;
        private final long size;

        private StoredArtifact(String path, String fileName, String artifactType, String mimeType, long size) {
            this.path = path;
            this.fileName = fileName;
            this.artifactType = artifactType;
            this.mimeType = mimeType;
            this.size = size;
        }

        public String getPath() {
            return path;
        }

        public String getFileName() {
            return fileName;
        }

        public String getArtifactType() {
            return artifactType;
        }

        public String getMimeType() {
            return mimeType;
        }

        public long getSize() {
            return size;
        }
    }
}
