package com.kwang.study.service.fs;

import cn.hutool.core.collection.CollectionUtil;
import com.kwang.study.cache.NodeCache;
import com.kwang.study.configuration.AppConfig;
import com.kwang.study.enums.FileChunkStatus;
import com.kwang.study.enums.NodeTypeEnum;
import com.kwang.study.enums.PermissionsEnum;
import com.kwang.study.filesystem.FileStorage;
import com.kwang.study.mapper.fs.FileChunkMapper;
import com.kwang.study.mapper.fs.HashRefNumMapper;
import com.kwang.study.mapper.fs.NodeMapper;
import com.kwang.study.pojo.fs.FileChunk;
import com.kwang.study.pojo.fs.HashRefNum;
import com.kwang.study.pojo.fs.Node;
import com.kwang.study.service.async.AsyncCleanupChunkService;
import com.kwang.study.utils.ChunkUtil;
import com.kwang.study.utils.HashUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import java.util.UUID;


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

    @Autowired
    private AppConfig appConfig;

    @Autowired
    private AsyncCleanupChunkService cleanupChunkService;

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

        Integer mimeTypeId = mimeTypeService.getMimeTypeId(mimeTypeName);
        if (mimeTypeId == null) {
            throw new IllegalArgumentException("文件类型名称未知");
        }
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
    @Transactional
    public void uploadChunk(Long fileId, Integer chunkIndex, InputStream chunkStream) throws IOException {
        Node node = nodeMapper.selectNodeById(fileId);
        if (node == null || !Objects.equals(NodeTypeEnum.CHUNK_INTERM.getCode(), node.getType())) {
            throw new IllegalArgumentException("Invalid fileId or the file is not in chunk uploading state.");
        }
        int chunkSize = appConfig.getFileStorage().getChunkSize();
        byte[] content = new byte[chunkSize];
        int readSize = ChunkUtil.readChunk(chunkStream, content);
        if (readSize == -1) {
            throw new IllegalArgumentException("上传文件大小超过：" + DataSize.ofBytes(content.length).toMegabytes());
        }

        String chunkKey = fileId + "-" + chunkIndex;
        chunkStorage.putFile(chunkKey, new ByteArrayInputStream(content, 0, readSize));

        FileChunk chunk = new FileChunk();
        chunk.setFileId(fileId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setKey(chunkKey);
        chunk.setStatus(FileChunkStatus.INIT.getCode());
        chunk.setSize(readSize);

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
        long startTime = System.currentTimeMillis();
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

        long endTime = System.currentTimeMillis();
        double executionTimeSeconds = (endTime - startTime) / 1000.0;
        // TODO: 6GB文件需要4分钟，需要改进。
        System.out.println("执行耗时: " + executionTimeSeconds + " 秒");

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
        if (!CollectionUtils.isEmpty(chunks)) {
            cleanupChunkService.cleanup(chunks);
        }

        // 7. 更新缓存
        invalidateParentCache(node.getParentId());
    }

    /**
     * 使父目录的缓存失效
     */
    private void invalidateParentCache(Long parentId) {
        nodeCache.deleteChildrenCache(parentId);
    }
}