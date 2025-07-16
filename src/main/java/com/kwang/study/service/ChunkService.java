package com.kwang.study.service;

import com.kwang.study.cache.NodeCache;
import com.kwang.study.enums.NodeTypeEnum;
import com.kwang.study.enums.PermissionsEnum;
import com.kwang.study.filesystem.FileStorage;
import com.kwang.study.mapper.FileChunkMapper;
import com.kwang.study.mapper.NodeMapper;
import com.kwang.study.pojo.FileChunk;
import com.kwang.study.pojo.Node;
import com.kwang.study.utils.HashUtil;
import lombok.extern.log4j.Log4j2;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

import static com.kwang.study.utils.HashUtil.bytesToHex;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_PARTIAL_CONTENT;

@Service
@Slf4j
public class ChunkService {
    @Autowired
    private FileChunkMapper fileChunkMapper;
    @Autowired
    @Qualifier("chunkStorage")
    private FileStorage chunkStorage;
    @Autowired
    private NodeMapper nodeMapper;
    @Autowired
    private NodeService nodeService;

    @Autowired
    @Qualifier("fileStorage")
    private FileStorage fileStorage;

    @Autowired
    private NodeCache nodeCache;

    // 初始化大文件节点
    public Node initBigFileNode(String name, Long parentId, String permissions) throws IOException {
        nodeService.validateParent(parentId);
        nodeService.checkNameUnique(parentId, name);

        String key = UUID.randomUUID().toString();
        Node node = new Node();
        node.setParentId(parentId);
        node.setName(name);
        node.setType(NodeTypeEnum.CHUNK_INTERM.getCode());
        node.setPermissions(permissions != null ? permissions : PermissionsEnum.ALL.getCode());
        node.setSize(0);
        node.setRefPath(key);
        node.setHash("");
        fileStorage.createFile(key);
        nodeMapper.insertNode(node);
        return node;
    }

    public void uploadChunk(Long fileId, Integer chunkIndex, InputStream chunkStream) throws IOException {
        String chunkKey = fileId + "/" + chunkIndex;
        chunkStorage.putFile(chunkKey, chunkStream);
        FileChunk chunk = new FileChunk();
        chunk.setFileId(fileId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setKey(chunkKey);
        fileChunkMapper.insertChunk(chunk);
    }

    public void mergeChunks(Long fileId) throws IOException {
        List<FileChunk> chunks = fileChunkMapper.selectAllByFileIdOrderByChunkIndex(fileId);
        Node node = nodeMapper.selectNodeById(fileId);
        MessageDigest digest = HashUtil.sha256();
        OutputStream outputStream = fileStorage.openFile(node.getRefPath());

        // 合并分片并计算哈希
        int size = 0;
        for (FileChunk chunk : chunks) {
            try (InputStream is = chunkStorage.getFile(chunk.getKey())) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    size += bytesRead;
                    digest.update(buffer, 0, bytesRead);
                    outputStream.write(buffer, 0, bytesRead);
                }
            }
        }
        outputStream.flush();
        outputStream.close();
        String hash = bytesToHex(digest.digest());
        // 更新节点信息
        nodeMapper.updateNodeForFile(fileId, node.getRefPath(), hash, size);

        // 清理分片
        new Thread(() -> {
            for (FileChunk chunk : chunks) {
                try {
                    chunkStorage.deleteFile(chunk.getKey());
                } catch (IOException e) {
                    log.error("删除分块错误：{}, {}", chunk.getFileId(), chunk.getChunkIndex());
                }
            }
        }).start();
        fileChunkMapper.deleteByFileId(fileId);
        if (node.getParentId() == null) {
            nodeCache.deleteRootChildren();
        } else {
            nodeCache.deleteChildrenCache(node.getParentId());
        }
    }

    // 下载文件（支持断点续传）
    public void downloadFile(Long fileId, HttpServletRequest request, HttpServletResponse response) throws IOException {
        Node node = nodeMapper.selectNodeById(fileId);
        InputStream is = fileStorage.getFile(node.getRefPath());
        if (is == null) {
            response.sendError(SC_NOT_FOUND);
            return;
        }

        // 处理Range请求
        long fileSize = node.getSize();
        long[] range = parseRangeHeader(request, fileSize);
        long start = range[0], end = range[1];

        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + node.getName() + "\"");
        response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
        response.setContentLengthLong(end - start + 1);
        response.setStatus(SC_PARTIAL_CONTENT);

        // 跳过起始字节
        long skipped = 0;
        while (skipped < start) {
            skipped += is.skip(start - skipped);
        }

        // 流式传输
        byte[] buffer = new byte[8192];
        long remaining = end - start + 1;
        OutputStream os = response.getOutputStream();
        int bytesRead;
        while (remaining > 0 && (bytesRead = is.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
            os.write(buffer, 0, bytesRead);
            remaining -= bytesRead;
        }
        os.flush();
    }

    // 工具方法：解析Range头
    private long[] parseRangeHeader(HttpServletRequest request, long fileSize) {
        String range = request.getHeader("Range");
        if (range == null || !range.startsWith("bytes=")) {
            return new long[]{0, fileSize - 1};
        }
        String[] parts = range.substring(6).split("-");
        long start = Long.parseLong(parts[0]);
        long end = parts.length > 1 && !parts[1].isEmpty() ? Long.parseLong(parts[1]) : fileSize - 1;
        return new long[]{start, end};
    }
}
