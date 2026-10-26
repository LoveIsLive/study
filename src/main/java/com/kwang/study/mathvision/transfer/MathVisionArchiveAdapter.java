package com.kwang.study.mathvision.transfer;

import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.fs.enums.ObjectTypeEnum;
import com.kwang.study.fs.exception.PathAlreadyExistsException;
import com.kwang.study.fs.mapper.NodeMapper;
import com.kwang.study.fs.mapper.FileChunkMapper;
import com.kwang.study.fs.storage.FileStorage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.io.*;

/** Master-only bridge. It never manipulates nodes outside its generated MathVision path. */
@Service
@ConditionalOnProperty(name = "mathvision.worker.enabled", havingValue = "false", matchIfMissing = true)
public class MathVisionArchiveAdapter {
    private final FileStorageService files;
    private final NodeMapper nodes;
    private final FileChunkMapper chunks;
    private final FileStorage chunkStorage;
    private final TransactionTemplate tx;
    private final com.kwang.study.fs.config.FSConfig fsConfig;
    public MathVisionArchiveAdapter(FileStorageService files, NodeMapper nodes, FileChunkMapper chunks,
            @Qualifier("chunkStorage") FileStorage chunkStorage, PlatformTransactionManager manager,
            com.kwang.study.fs.config.FSConfig fsConfig) {
        this.files = files; this.nodes = nodes; this.chunks = chunks; this.chunkStorage = chunkStorage;
        this.tx = new TransactionTemplate(manager);
        this.fsConfig = fsConfig;
    }
    public synchronized void archive(Path source, String path, String mime, String expectedHash) throws IOException {
        if (!path.matches("/mathvision/task-[0-9]+/v[0-9]+/runs/[a-f0-9-]{36}/[a-f0-9]{64}\\.(mp4|html)"))
            throw new IllegalArgumentException("Invalid archive path");
        var previous = nodes.selectNodeByPath(path);
        if (previous != null && ObjectTypeEnum.FILE.getCode().equals(previous.getType())) {
            try (InputStream input = files.getFileObject(path).getContent()) {
                if (!hash(input).equals(expectedHash)) throw new IOException("Existing archive checksum mismatch");
            }
            return;
        }
        // Existing platform upload IDs are JVM-local. Recreate only our own incomplete archive.
        if (previous != null) {
            if (!ObjectTypeEnum.CHUNK_INTERM.getCode().equals(previous.getType()) || previous.getHashId() != null)
                throw new IOException("Unexpected archive node type");
            var abandoned = chunks.selectAllByFileIdOrderByChunkIndex(previous.getId());
            tx.executeWithoutResult(status -> {
                chunks.deleteByFileId(previous.getId()); nodes.deleteNodeById(previous.getId());
            });
            for (var chunk : abandoned) chunkStorage.deleteFile(chunk.getKey());
        }
        StringBuilder parent = new StringBuilder();
        String[] parts = path.substring(1).split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            parent.append('/').append(parts[i]);
            try { files.createDirectory(parent.toString()); } catch (PathAlreadyExistsException ignored) { }
        }
        String resolvedMime = files.getMimeTypeId(mime).getMimeTypeId() != null ? mime : "application/octet-stream";
        String uploadId = files.initMultiUpload(path, resolvedMime).getUploadId();
        // The native store uses index * configuredChunkSize for its maximum-size check.
        int chunkBytes = fsConfig.getChunkSize();
        if (chunkBytes <= 0 || chunkBytes > 64 * 1024 * 1024) throw new IOException("Unsupported platform chunk size");
        int total = (int) ((Files.size(source) + chunkBytes - 1) / chunkBytes);
        try (InputStream input = Files.newInputStream(source)) {
            for (int index = 0; index < total; index++) {
                byte[] part = input.readNBytes(chunkBytes);
                files.uploadChunk(uploadId, index, total, new ByteArrayInputStream(part));
            }
        }
        // The platform merger isn't transactional itself. Scope its metadata writes here.
        tx.executeWithoutResult(status -> {
            try { files.mergeChunk(uploadId, total); }
            catch (IOException error) { throw new java.io.UncheckedIOException(error); }
        });
    }
    private String hash(InputStream input) throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536];
            for (int n; (n = input.read(buffer)) >= 0;) digest.update(buffer, 0, n);
            return MathVisionTransferClient.hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public synchronized void deleteUnreferenced(String path) throws IOException {
        if (!path.matches("/mathvision/task-[0-9]+/v[0-9]+/runs/[a-f0-9-]{36}/[a-f0-9]{64}\\.(mp4|html)"))
            throw new IllegalArgumentException("Invalid archive path");
        var node = nodes.selectNodeByPath(path);
        if (node == null) return;
        if (ObjectTypeEnum.FILE.getCode().equals(node.getType())) { files.deleteFileObject(path); return; }
        if (ObjectTypeEnum.CHUNK_INTERM.getCode().equals(node.getType()) && node.getHashId() == null) {
            var abandoned = chunks.selectAllByFileIdOrderByChunkIndex(node.getId());
            tx.executeWithoutResult(status -> { chunks.deleteByFileId(node.getId()); nodes.deleteNodeById(node.getId()); });
            for (var chunk : abandoned) chunkStorage.deleteFile(chunk.getKey());
        }
    }
}
