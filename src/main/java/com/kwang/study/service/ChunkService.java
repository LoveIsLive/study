package com.kwang.study.service;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.collection.ListUtil;
import com.kwang.study.cache.NodeCache;
import com.kwang.study.dto.NodeDetailDTO;
import com.kwang.study.enums.FileChunkStatus;
import com.kwang.study.enums.NodeTypeEnum;
import com.kwang.study.enums.PermissionsEnum;
import com.kwang.study.filesystem.FileStorage;
import com.kwang.study.mapper.FileChunkMapper;
import com.kwang.study.mapper.HashRefNumMapper;
import com.kwang.study.mapper.NodeMapper;
import com.kwang.study.pojo.FileChunk;
import com.kwang.study.pojo.HashRefNum;
import com.kwang.study.pojo.Node;
import com.kwang.study.utils.HashUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
    @Qualifier("fileStorage")
    private FileStorage fileStorage;

    @Autowired
    private NodeMapper nodeMapper;

    @Autowired
    private NodeService nodeService;

    @Autowired
    private HashRefNumMapper hashRefNumMapper;
    @Autowired
    private MimeTypeService mimeTypeService;

    @Autowired
    private NodeCache nodeCache;

    /**
     * 初始化一个大文件上传节点，该节点是中间状态。
     *
     * @param name        文件名
     * @param parentId    父目录ID
     * @param permissions 权限字符串
     * @return 创建的中间态节点
     */
    @Transactional
    public Node initBigFileNode(String name, Long parentId, String permissions, String mimeTypeName) {
        nodeService.validateParent(parentId);
        if (!StringUtils.hasText(mimeTypeName)) {
            throw new IllegalArgumentException("mimeTypeName cannot is null");
        }

        Integer mimeTypeId = mimeTypeService.getOrCreateMimeTypeId(mimeTypeName);
        Node node = new Node();
        node.setParentId(parentId);
        node.setName(name);
        // 设置为分块上传中间态
        node.setType(NodeTypeEnum.CHUNK_INTERM.getCode());
        node.setPermissions(permissions != null ? permissions : PermissionsEnum.ALL.getCode());
        // 此时文件大小为0，也没有hash
        node.setSize(0L);
        // hash_id 暂时为空
        node.setHashId(null);
        node.setMimeTypeId(mimeTypeId);

        // 先插入数据库获取node id
        nodeMapper.insertNode(node);

        // 更新缓存
        invalidateParentCache(parentId);
        return node;
    }

    /**
     * 上传单个文件分片
     *
     * @param fileId      关联的node.id
     * @param chunkIndex  分片索引
     * @param chunkStream 分片内容的输入流
     * @throws IOException IO异常
     */
    public void uploadChunk(Long fileId, Integer chunkIndex, InputStream chunkStream) throws IOException {
        Node node = nodeMapper.selectNodeById(fileId);
        if (node == null || !Objects.equals(NodeTypeEnum.CHUNK_INTERM.getCode(), node.getType())) {
            throw new IllegalArgumentException("Invalid fileId or the file is not in chunk uploading state.");
        }
        String chunkKey = fileId + "/" + chunkIndex;
        chunkStorage.putFile(chunkKey, chunkStream);

        FileChunk chunk = new FileChunk();
        chunk.setFileId(fileId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setKey(chunkKey);
        chunk.setStatus(FileChunkStatus.INIT.getCode());

        fileChunkMapper.insertChunk(chunk);
    }

    /**
     * 统计已上传成功的分片数量
     *
     * @param fileId 关联的node.id
     * @return 已上传的分片数量
     */
    public int countUploadedChunks(Long fileId) {
        return fileChunkMapper.countChunksByStatus(fileId, FileChunkStatus.INIT.getCode());
    }

    /**
     * 合并所有分片
     *
     * @param fileId 关联的node.id
     * @throws IOException IO异常
     */
    @Transactional
    public void mergeChunks(Long fileId) throws IOException {
        Node node = nodeMapper.selectNodeById(fileId);
        if (node == null || !Objects.equals(NodeTypeEnum.CHUNK_INTERM.getCode(), node.getType())) {
            log.warn("Attempted to merge chunks for a non-existent or invalid node: {}", fileId);
            return;
        }

        // 1. 更新分片状态为合并中
        fileChunkMapper.updateAllStatusByFileId(fileId, FileChunkStatus.MERGING.getCode());

        List<FileChunk> chunks = fileChunkMapper.selectAllByFileIdOrderByChunkIndex(fileId);
        if (CollectionUtil.isEmpty(chunks)) {
            fileChunkMapper.updateAllStatusByFileId(fileId, FileChunkStatus.MERGE_FAIL.getCode());
            throw new IllegalStateException("No chunks found for fileId: " + fileId);
        }

        long totalSize = 0;
        MessageDigest sha256 = HashUtil.sha256();
        String tempFileKey = UUID.randomUUID().toString(); // 创建一个临时的key用于合并

        // 2. 合并分片到临时文件并计算哈希和大小
        try (OutputStream os = fileStorage.openFile(tempFileKey)) {
            for (FileChunk chunk : chunks) {
                try (InputStream is = chunkStorage.getFile(chunk.getKey())) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                        sha256.update(buffer, 0, bytesRead);
                        totalSize += bytesRead;
                    }
                }
            }
        } catch (IOException e) {
            fileChunkMapper.updateAllStatusByFileId(fileId, FileChunkStatus.MERGE_FAIL.getCode());
            fileStorage.deleteFile(tempFileKey); // 合并失败，删除临时文件
            throw new IOException("Failed to merge chunks for fileId: " + fileId, e);
        }

        String hash = HashUtil.bytesToHex(sha256.digest());

        // 3. 处理文件哈希引用
        HashRefNum hashRefNum = hashRefNumMapper.selectByHashForUpdate(hash);
        if (hashRefNum != null) {
            // 已存在相同文件，增加引用计数
            hashRefNumMapper.incrementRefNum(hashRefNum.getId());
            // 删除刚刚上传的临时文件
            fileStorage.deleteFile(tempFileKey);
        } else {
            // 新文件，创建引用记录
            hashRefNum = new HashRefNum();
            hashRefNum.setHash(hash);
            hashRefNum.setRefPath(tempFileKey);
            hashRefNum.setRefNum(1);
            hashRefNum.setSize(totalSize);
            hashRefNumMapper.insertHash(hashRefNum);
        }

        // 4. 更新节点信息，将其从中间态转为正式文件
        Node updateNode = new Node();
        updateNode.setId(fileId);
        updateNode.setType(NodeTypeEnum.FILE.getCode());
        updateNode.setSize(totalSize);
        updateNode.setHashId(hashRefNum.getId());
        updateNode.setModifyTime(null); // 由数据库自动更新
        nodeMapper.updateNode(updateNode);

        // 5. 更新状态
        fileChunkMapper.updateAllStatusByFileId(fileId, FileChunkStatus.MERGE_SUCCESS.getCode());

        // 6. 异步清理分片记录和物理文件
        cleanupChunksAsync(chunks);

        // 7. 更新缓存
        invalidateParentCache(node.getParentId());
    }

    /**
     * 异步清理分片数据
     */
    private void cleanupChunksAsync(List<FileChunk> chunks) {
        if (CollectionUtils.isEmpty(chunks)) return;
        Long fileId = chunks.get(0).getFileId();
        CompletableFuture.runAsync(() -> {
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
        });
    }

    /**
     * 下载文件（支持断点续传）
     *
     * @param fileId   文件ID
     * @param request  HTTP请求
     * @param response HTTP响应
     * @throws IOException IO异常
     */
    public void downloadFile(Long fileId, HttpServletRequest request, HttpServletResponse response) throws IOException {
        NodeDetailDTO node = nodeMapper.selectNodeDetailById(fileId);
        if (node == null || Objects.equals(NodeTypeEnum.DIR.getCode(), node.getType())) {
            response.sendError(SC_NOT_FOUND, "File not found");
            return;
        }

        HashRefNum hashRefNum = node.getHashId() != null ? hashRefNumMapper.selectById(node.getHashId()) : null;
        if (hashRefNum == null) {
            response.sendError(SC_NOT_FOUND, "File data not found");
            return;
        }

        String fileKey = hashRefNum.getRefPath();

        try (InputStream is = fileStorage.getFile(fileKey)) {
            if (is == null) {
                response.sendError(SC_NOT_FOUND, "File data not found");
                return;
            }

            long fileSize = node.getSize();
            // 处理Range请求
            long[] range = parseRangeHeader(request, fileSize);
            long start = range[0];
            long end = range[1];
            long length = end - start + 1;

            response.setContentType(node.getMimeTypeName() != null ? node.getMimeTypeName() : "application/octet-stream");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + node.getName() + "\"");
            response.setHeader("Accept-Ranges", "bytes");

            // 根据是否是范围请求设置不同的响应头
            if (request.getHeader("Range") != null) {
                response.setStatus(SC_PARTIAL_CONTENT);
                response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
                response.setContentLengthLong(length);
            } else {
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentLengthLong(fileSize);
            }

            // 跳过起始字节
            if (start > 0) {
                long bytesToSkip = start;
                while (bytesToSkip > 0) {
                    long skipped = is.skip(bytesToSkip);
                    if (skipped <= 0) {
                        // 如果无法再跳过任何字节，但还没到目标位置，说明流出了问题
                        throw new IOException("Unable to skip to the specified start position.");
                    }
                    bytesToSkip -= skipped;
                }
            }

            // 流式传输
            try (OutputStream os = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                long bytesToWrite = length;
                while (bytesToWrite > 0 && (bytesRead = is.read(buffer, 0, (int) Math.min(buffer.length, bytesToWrite))) != -1) {
                    os.write(buffer, 0, bytesRead);
                    bytesToWrite -= bytesRead;
                }
                os.flush();
            }
        }
    }

    /**
     * 解析Range头，返回[start, end]
     */
    private long[] parseRangeHeader(HttpServletRequest request, long fileSize) {
        String rangeHeader = request.getHeader("Range");
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
            return new long[]{0, fileSize - 1};
        }
        // "bytes=0-499" or "bytes=500-" or "bytes=-500"
        String rangeValue = rangeHeader.substring(6);
        long start = 0, end = fileSize - 1;

        if (rangeValue.startsWith("-")) { // e.g., "-500" (last 500 bytes)
            long lastBytes = Long.parseLong(rangeValue.substring(1));
            start = Math.max(0, fileSize - lastBytes);
        } else {
            String[] parts = rangeValue.split("-");
            start = Long.parseLong(parts[0]);
            if (parts.length > 1 && !parts[1].isEmpty()) {
                end = Long.parseLong(parts[1]);
            }
        }

        // 保证范围有效
        if (start < 0 || start >= fileSize || start > end) {
            return new long[]{0, fileSize - 1};
        }
        return new long[]{start, Math.min(end, fileSize - 1)};
    }

    /**
     * 使父目录的缓存失效
     */
    private void invalidateParentCache(Long parentId) {
        if (parentId == null) {
            nodeCache.deleteRootChildren();
        } else {
            nodeCache.deleteChildrenCache(parentId);
        }
    }
}