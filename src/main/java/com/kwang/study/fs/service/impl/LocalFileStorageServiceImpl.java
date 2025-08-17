package com.kwang.study.fs.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.kwang.study.fs.enums.FileChunkStatus;
import com.kwang.study.fs.config.FSConfig;
import com.kwang.study.fs.enums.ObjectTypeEnum;
import com.kwang.study.fs.dto.result.*;
import com.kwang.study.fs.exception.*;
import com.kwang.study.fs.mapper.FileChunkMapper;
import com.kwang.study.fs.mapper.HashRefNumMapper;
import com.kwang.study.fs.mapper.NodeMapper;
import com.kwang.study.fs.pojo.FileChunk;
import com.kwang.study.fs.pojo.HashRefNum;
import com.kwang.study.fs.pojo.Node;
import com.kwang.study.fs.pojo.NodeDetail;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.fs.storage.FileStorage;
import com.kwang.study.fs.util.ChunkUtil;
import com.kwang.study.fs.util.HashUtil;
import com.kwang.study.fs.service.async.AsyncCleanupChunkService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.kwang.study.utils.PathUtils.*;

@Service
@Slf4j
public class LocalFileStorageServiceImpl implements FileStorageService {
    @Autowired
    private NodeMapper nodeMapper;
    @Autowired
    private FileChunkMapper fileChunkMapper;
    @Autowired
    private HashRefNumMapper hashRefNumMapper;
    @Autowired
    private MimeTypeService mimeTypeService;

    @Autowired
    @Qualifier("fileStorage")
    private FileStorage fileStorage;

    @Autowired
    @Qualifier("chunkStorage")
    private FileStorage chunkStorage;

    @Autowired
    private FSConfig fsConfig;

    @Autowired
    private AsyncCleanupChunkService cleanupChunkService;

    private final ConcurrentHashMap<Long, Object> mapLock = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Long> uploadIdToFileId = new ConcurrentHashMap<>();

    @Override
    @Transactional
    public VoidResult createDirectory(String path) {
        if (!isOrdinaryPath(path))
            throw new InvalidPathException();
        ResolvedPath resolvedPath = parsePath(path);

        NodeDetail parentNode = nodeMapper.selectNodeDetailByPath(resolvedPath.getParentPath());
        if (parentNode == null) {
            throw new PathNotFoundException(resolvedPath.getParentPath());
        }
        if (!Objects.equals(parentNode.getType(), ObjectTypeEnum.DIR.getCode())) {
            throw new NotADirectoryException(resolvedPath.getParentPath());
        }

        if (nodeMapper.selectNodeByParentIdAndName(parentNode.getId(), resolvedPath.getName()) != null) {
            throw new PathAlreadyExistsException(path);
        }

        // 4. 创建新节点
        Node node = new Node();
        node.setParentId(parentNode.getId());
        node.setName(resolvedPath.getName());
        node.setType(ObjectTypeEnum.DIR.getCode());
        node.setSize(0L);
        // 目录没有hashId和mimeType
        node.setHashId(null);
        node.setMimeTypeId(null);

        nodeMapper.insertNode(node);

        return VoidResult.success();
    }

    @Override
    @Transactional
    public VoidResult createFile(String path, InputStream fileStream, String mimeTypeName) throws IOException {
        if (fileStream == null)
            throw new IllegalArgumentException("输入流为空");
        if (!isOrdinaryPath(path))
            throw new InvalidPathException();
        Integer mimeTypeId = mimeTypeService.getMimeTypeId(mimeTypeName);
        if (mimeTypeId == null) {
            throw new IllegalArgumentException("文件类型名称未知");
        }

        ResolvedPath resolvedPath = parsePath(path);
        NodeDetail parentNode = nodeMapper.selectNodeDetailByPath(resolvedPath.getParentPath());
        if (parentNode == null) {
            throw new PathNotFoundException(resolvedPath.getParentPath());
        }
        if (!Objects.equals(parentNode.getType(), ObjectTypeEnum.DIR.getCode())) {
            throw new NotADirectoryException(resolvedPath.getParentPath());
        }

        if (nodeMapper.selectNodeByParentIdAndName(parentNode.getId(), resolvedPath.getName()) != null) {
            throw new PathAlreadyExistsException(path);
        }

        int chunkSize = fsConfig.getChunkSize();
        byte[] content = new byte[chunkSize];
        int readSize = ChunkUtil.readChunk(fileStream, content);
        if (readSize == -1) {
            throw new IllegalArgumentException("上传文件大小超过：" + DataSize.ofBytes(content.length).toMegabytes());
        }

        String hash = HashUtil.sha256Hash(content, 0, readSize);
        HashRefNum hashRefNum = hashRefNumMapper.selectByHashForUpdate(hash);
        String fileKey;
        if (hashRefNum != null) {
            // 文件已存在，增加引用计数
            hashRefNumMapper.incrementRefNum(hashRefNum.getId());
        } else {
            // 新文件，存入文件存储并创建记录
            fileKey = UUID.randomUUID().toString();
            fileStorage.putFile(fileKey, new ByteArrayInputStream(content));

            hashRefNum = new HashRefNum();
            hashRefNum.setHash(hash);
            hashRefNum.setRefPath(fileKey);
            hashRefNum.setRefNum(1);
            hashRefNum.setSize((long) readSize);
            hashRefNumMapper.insertHash(hashRefNum);
        }

        Node node = new Node();
        node.setParentId(parentNode.getId());
        node.setName(resolvedPath.getName());
        node.setType(ObjectTypeEnum.FILE.getCode());
        node.setSize((long) readSize);
        node.setHashId(hashRefNum.getId());
        node.setMimeTypeId(mimeTypeId);

        nodeMapper.insertNode(node);

        return VoidResult.success();
    }

    @Override
    @Transactional
    public VoidResult deleteFileObject(String path) throws IOException {
        if (!isOrdinaryPath(path))
            throw new InvalidPathException();

        NodeDetail nodeDetail = nodeMapper.selectNodeDetailByPath(path);
        if (nodeDetail == null) {
            throw new PathNotFoundException(path);
        }
        if (!Objects.equals(nodeDetail.getType(), ObjectTypeEnum.FILE.getCode())) {
            throw new NotAFileException(path);
        }

        // 1. 删除节点记录
        nodeMapper.deleteNodeById(nodeDetail.getId());

        // 2. 处理哈希引用计数
        if (nodeDetail.getHashId() != null) {
            HashRefNum hashRefNum = hashRefNumMapper.selectByIdForUpdate(nodeDetail.getHashId());
            if (hashRefNum != null) {
                if (hashRefNum.getRefNum() <= 1) {
                    fileStorage.deleteFile(hashRefNum.getRefPath());
                    hashRefNumMapper.deleteById(hashRefNum.getId());
                } else {
                    hashRefNumMapper.decrementRefNum(hashRefNum.getId());
                }
            }
        }

        return VoidResult.success();
    }

    @Override
    @Transactional
    public VoidResult deleteDirObject(String path) throws IOException {
        if (!isOrdinaryPath(path))
            throw new InvalidPathException();

        NodeDetail nodeDetail = nodeMapper.selectNodeDetailByPath(path);
        if (nodeDetail == null) {
            throw new PathNotFoundException(path);
        }
        if (!Objects.equals(nodeDetail.getType(), ObjectTypeEnum.DIR.getCode())) {
            throw new NotADirectoryException(path);
        }

        // 1. 获取所有后代节点ID
        List<Long> descendantIdResult = nodeMapper.selectAllDescendantIds(nodeDetail.getId());
        List<Long> descendantIds = new ArrayList<>(descendantIdResult);
        // 加上自身ID
        descendantIds.add(nodeDetail.getId());

        // 2. 批量查询所有后代中的文件节点，以处理其哈希引用
        List<Node> allDeleteNodes = nodeMapper.selectNodesByIds(descendantIds);
        List<Node> allFileDeleteNodes = allDeleteNodes.stream()
                .filter(n -> Objects.equals(n.getType(), ObjectTypeEnum.FILE.getCode()))
                .collect(Collectors.toList());

        // 3. 按hashId分组，高效处理引用计数
        Map<Long, Long> hashIdCounts = allFileDeleteNodes.stream()
                .filter(n -> n.getHashId() != null)
                .collect(Collectors.groupingBy(Node::getHashId, Collectors.counting()));

        for (Map.Entry<Long, Long> entry : hashIdCounts.entrySet()) {
            Long hashId = entry.getKey();
            long countToDelete = entry.getValue();

            HashRefNum lockedHashRef = hashRefNumMapper.selectByIdForUpdate(hashId);
            if (lockedHashRef != null) {
                if (lockedHashRef.getRefNum() <= countToDelete) {
                    fileStorage.deleteFile(lockedHashRef.getRefPath());
                    hashRefNumMapper.deleteById(lockedHashRef.getId());
                } else {
                    hashRefNumMapper.batchDecrementRefNum(hashId, countToDelete);
                }
            }
        }

        // 4. 批量删除数据库中的所有节点（包括目录和已经被处理过的文件节点记录）
        nodeMapper.batchDeleteNodeByIds(descendantIds);

        return VoidResult.success();
    }

    @Override
    @Transactional
    public VoidResult updateFileObject(String path, String newName, InputStream fileStream, String mimeTypeName) throws IOException {
        if (!isOrdinaryPath(path))
            throw new InvalidPathException();
        if (newName == null && fileStream == null && mimeTypeName == null)
            return VoidResult.success();

        Integer mimeTypeId = null;
        if (mimeTypeName != null) {
            mimeTypeId = mimeTypeService.getMimeTypeId(mimeTypeName);
            if (mimeTypeId == null) {
                throw new IllegalArgumentException("文件类型名称未知");
            }
        }

        NodeDetail originalNode = nodeMapper.selectNodeDetailByPath(path);
        if (originalNode == null) {
            throw new PathNotFoundException(path);
        }
        if (!Objects.equals(originalNode.getType(), ObjectTypeEnum.FILE.getCode())) {
            throw new NotAFileException(path);
        }

        Node node = new Node();
        BeanUtils.copyProperties(originalNode, node);

        if (mimeTypeId != null) {
            node.setMimeTypeId(mimeTypeId);
        }

        if (newName != null) {
            if (!isValidName(newName)) {
                throw new IllegalArgumentException("不合法的路径名称");
            }
            if (nodeMapper.selectNodeByParentIdAndName(originalNode.getParentId(), newName) != null) {
                throw new PathAlreadyExistsException("original path:" + path + ", newName:" + newName);
            }
            node.setName(newName);
        }

        if (fileStream != null) {
            int chunkSize = fsConfig.getChunkSize();
            byte[] content = new byte[chunkSize];
            int readSize = ChunkUtil.readChunk(fileStream, content);
            if (readSize == -1) {
                throw new IllegalArgumentException("上传文件大小超过：" + DataSize.ofBytes(content.length).toMegabytes());
            }

            String hash = HashUtil.sha256Hash(content, 0, readSize);
            HashRefNum hashRefNum = hashRefNumMapper.selectByHashForUpdate(hash);
            String fileKey;
            if (hashRefNum != null) {
                // 文件已存在，增加引用计数
                hashRefNumMapper.incrementRefNum(hashRefNum.getId());
            } else {
                // 新文件，存入文件存储并创建记录
                fileKey = UUID.randomUUID().toString();
                fileStorage.putFile(fileKey, new ByteArrayInputStream(content));

                hashRefNum = new HashRefNum();
                hashRefNum.setHash(hash);
                hashRefNum.setRefPath(fileKey);
                hashRefNum.setRefNum(1);
                hashRefNum.setSize((long) readSize);
                hashRefNumMapper.insertHash(hashRefNum);
            }

            // 删除之前的文件
            if (originalNode.getHashId() != null) {
                HashRefNum oldHashRefNum = hashRefNumMapper.selectByIdForUpdate(originalNode.getHashId());
                if (oldHashRefNum != null) {
                    if (oldHashRefNum.getRefNum() <= 1) {
                        fileStorage.deleteFile(oldHashRefNum.getRefPath());
                        hashRefNumMapper.deleteById(oldHashRefNum.getId());
                    } else {
                        hashRefNumMapper.decrementRefNum(oldHashRefNum.getId());
                    }
                }
            }
            node.setHashId(hashRefNum.getId());
            node.setSize((long) readSize);
        }

        nodeMapper.updateNode(node);

        return VoidResult.success();
    }

    @Override
    @Transactional
    public VoidResult updateDirObject(String path, String newName) {
        if (!isOrdinaryPath(path))
            throw new InvalidPathException();
        if (newName == null)
            return VoidResult.success();
        if (!isValidName(newName)) {
            throw new IllegalArgumentException("不合法的目录名称" + newName);
        }

        NodeDetail originalNode = nodeMapper.selectNodeDetailByPath(path);
        if (originalNode == null) {
            throw new PathNotFoundException(path);
        }
        if (!Objects.equals(originalNode.getType(), ObjectTypeEnum.DIR.getCode())) {
            throw new NotADirectoryException(path);
        }

        if (nodeMapper.selectNodeByParentIdAndName(originalNode.getParentId(), newName) != null) {
            throw new PathAlreadyExistsException("original path:" + path + ", newName:" + newName + ", already exist");
        }

        Node node = new Node();
        BeanUtils.copyProperties(originalNode, node);
        node.setName(newName);

        nodeMapper.updateNode(node);

        return VoidResult.success();
    }

    @Override
    @Transactional
    public DirObjectResult getDirectoryObject(String path) {
        if (!isValidPath(path))
            throw new InvalidPathException();

        NodeDetail nodeDetail = null;
        if (!"/".equals(path)) {
            nodeDetail = nodeMapper.selectNodeDetailByPath(path);
            if (nodeDetail == null)
                throw new PathNotFoundException(path);
            if (!Objects.equals(nodeDetail.getType(), ObjectTypeEnum.DIR.getCode())) {
                throw new NotADirectoryException(path);
            }
        }

        List<NodeDetail> children = nodeMapper.selectChildrenDetailByParentId(nodeDetail == null ?
                null : nodeDetail.getId());
        DirObjectResult result = new DirObjectResult();
        result.setSuccess(Boolean.TRUE);
        if (nodeDetail != null) {
            BeanUtils.copyProperties(nodeDetail, result);
        }
        result.setFileObjectDescs(children.stream().map(node -> {
            DirObjectResult.FileObjectDesc desc = new DirObjectResult.FileObjectDesc();
            BeanUtils.copyProperties(node, desc);
            return desc;
        }).collect(Collectors.toList()));
        return result;
    }

    @Override
    @Transactional
    public FileObjectResult getFileObject(String path) throws IOException {
        if (!isOrdinaryPath(path))
            throw new InvalidPathException();

        NodeDetail nodeDetail = nodeMapper.selectNodeDetailByPath(path);
        if (nodeDetail == null) {
            throw new PathNotFoundException(path);
        }
        if (!Objects.equals(nodeDetail.getType(), ObjectTypeEnum.FILE.getCode())) {
            throw new NotAFileException(path);
        }
        FileObjectResult result = new FileObjectResult();
        result.setSuccess(Boolean.TRUE);
        BeanUtils.copyProperties(nodeDetail, result);
        if (nodeDetail.getHashId() != null) {
            HashRefNum hashRefNum = hashRefNumMapper.selectById(nodeDetail.getHashId());
            if (hashRefNum != null) {
                result.setContent(fileStorage.getFile(hashRefNum.getRefPath()));
            }
        }
        return result;
    }

    @Override
    public GenericObjectResult getObjectDesc(String path) throws IOException {
        if (!isOrdinaryPath(path))
            throw new InvalidPathException();

        NodeDetail nodeDetail = nodeMapper.selectNodeDetailByPath(path);
        if (nodeDetail == null) {
            throw new PathNotFoundException(path);
        }
        GenericObjectResult result = null;
        if (Objects.equals(nodeDetail.getType(), ObjectTypeEnum.DIR.getCode())) {
            result = new DirObjectResult();
        } else {
            result = new FileObjectDescResult();
        }
        result.setSuccess(Boolean.TRUE);
        BeanUtils.copyProperties(nodeDetail, result);
        return result;
    }

    /*
    分块上传方法
     */

    @Override
    @Transactional
    public InitMultiUploadResult initMultiUpload(String path, String mimeTypeName) throws IOException {
        if (!isOrdinaryPath(path))
            throw new InvalidPathException();

        Integer mimeTypeId = mimeTypeService.getMimeTypeId(mimeTypeName);
        if (mimeTypeId == null) {
            throw new IllegalArgumentException("文件类型名称未知");
        }

        ResolvedPath resolvedPath = parsePath(path);
        NodeDetail parentNode = nodeMapper.selectNodeDetailByPath(resolvedPath.getParentPath());
        if (parentNode == null) {
            throw new PathNotFoundException(resolvedPath.getParentPath());
        }
        if (!Objects.equals(parentNode.getType(), ObjectTypeEnum.DIR.getCode())) {
            throw new NotADirectoryException(resolvedPath.getParentPath());
        }

        if (nodeMapper.selectNodeByParentIdAndName(parentNode.getId(), resolvedPath.getName()) != null) {
            throw new PathAlreadyExistsException(path);
        }

        Node node = new Node();
        node.setParentId(parentNode.getId());
        node.setName(resolvedPath.getName());
        // 设置为分块上传中间态
        node.setType(ObjectTypeEnum.CHUNK_INTERM.getCode());
        // 此时文件大小为0，也没有hash
        node.setSize(0L);
        // hash_id 暂时为空
        node.setHashId(null);
        node.setMimeTypeId(mimeTypeId);

        // 先插入数据库获取node id
        nodeMapper.insertNode(node);

        Long fileId = node.getId();
        String uploadId = UUID.randomUUID().toString();
        Long previous = uploadIdToFileId.putIfAbsent(uploadId, fileId);
        if (previous != null)
            throw new IllegalStateException("分块上传UUID重复");

        InitMultiUploadResult result = new InitMultiUploadResult();
        result.setSuccess(Boolean.TRUE);
        BeanUtils.copyProperties(node, result);
        result.setUploadId(uploadId);
        return result;
    }

    @Override
    @Transactional
    public UploadChunkResult uploadChunkAndAutoMerge(String uploadId, Integer chunkIndex,
                                                     Integer totalChunks, InputStream chunkStream) throws IOException {
        if (chunkIndex == null || totalChunks == null || chunkIndex < 0 || chunkIndex >= totalChunks)
            throw new IllegalArgumentException("chunkIndex:" + chunkIndex + ", totalChunks:" + totalChunks);
        if (chunkStream == null)
            throw new IllegalArgumentException("输入流为空");
        Long fileId = uploadIdToFileId.get(uploadId);
        if (fileId == null)
            throw new IllegalArgumentException("uploadId不存在");

        UploadChunkResult result = new UploadChunkResult();
        this.uploadChunk(fileId, chunkIndex, chunkStream);
        int uploadedCount = this.countUploadedChunks(fileId);

        if (uploadedCount == totalChunks) {
            Object val = mapLock.putIfAbsent(fileId, new Object());
            if (val != null) {
                log.info("并发合并，fileId: {}", fileId);
            } else {
                try {
                    this.mergeChunks(fileId);
                } finally {
                    mapLock.remove(fileId);
                }
            }
            result.setMerged(Boolean.TRUE);
            uploadIdToFileId.remove(uploadId); // Note
        } else {
            result.setMerged(Boolean.FALSE);
        }
        result.setSuccess(Boolean.TRUE);
        result.setUploadNum(uploadedCount);
        result.setSuccess(Boolean.TRUE);
        return result;
    }

    @Override
    public VoidResult searchNodesBFS(String path, String namePattern, Consumer<SearchNodeResult> resultConsumer) {
        if (!isValidPath(path))
            throw new InvalidPathException();

        NodeDetail nodeDetail = null;
        if (!"/".equals(path)) {
            nodeDetail = nodeMapper.selectNodeDetailByPath(path);
            if (nodeDetail == null)
                throw new PathNotFoundException(path);
            if (!Objects.equals(nodeDetail.getType(), ObjectTypeEnum.DIR.getCode())) {
                throw new NotADirectoryException(path);
            }
        }

        Queue<SearchQueueItem> directoryQueue = new LinkedList<>();
        Long startNodeId = nodeDetail == null ? null : nodeDetail.getId();
        // 初始化队列
        if (startNodeId == null) {
            directoryQueue.add(new SearchQueueItem(null, "/"));
        } else {
            if (!path.endsWith("/"))
                path += "/";
            directoryQueue.add(new SearchQueueItem(startNodeId, path));
        }

        String lowerCasePattern = namePattern.toLowerCase();

        while (!directoryQueue.isEmpty()) {
            SearchQueueItem current = directoryQueue.poll();

            List<Node> children = nodeMapper.selectChildrenByParentId(current.nodeId);

            if (CollectionUtils.isEmpty(children)) {
                continue;
            }

            for (Node child : children) {
                // 安全地构建子节点的完整路径
                String childFullPath = current.path + child.getName();

                // 检查名称是否匹配
                if (child.getName().toLowerCase().contains(lowerCasePattern)) {
                    SearchNodeResult item = new SearchNodeResult();
                    BeanUtils.copyProperties(child, item);
                    item.setFullPath(childFullPath);
                    resultConsumer.accept(item);
                }

                // 如果是目录，则加入队列以便继续搜索其子目录
                if (Objects.equals(child.getType(), ObjectTypeEnum.DIR.getCode())) {
                    directoryQueue.add(new SearchQueueItem(child.getId(), childFullPath + "/"));
                }
            }
        }

        return VoidResult.success();
    }

    @Override
    public MimeTypeResult getAllMimeTypeNames() {
        MimeTypeResult result = new MimeTypeResult();
        List<String> typeNames = mimeTypeService.getAllMimeTypeNames();
        result.setSuccess(Boolean.TRUE);
        result.setMimeTypeNames(typeNames);
        return result;
    }

    /**
     * 上传单个文件分片
     *
     * @param fileId      关联的node.id
     * @param chunkIndex  分片索引
     * @param chunkStream 分片内容的输入流
     * @throws IOException IO异常
     */
    private void uploadChunk(Long fileId, Integer chunkIndex, InputStream chunkStream) throws IOException {
        Node node = nodeMapper.selectNodeById(fileId);
        if (node == null || !Objects.equals(ObjectTypeEnum.CHUNK_INTERM.getCode(), node.getType())) {
            throw new IllegalArgumentException("Invalid fileId or the file is not in chunk uploading state.");
        }
        int chunkSize = fsConfig.getChunkSize();
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
    private int countUploadedChunks(Long fileId) {
        return fileChunkMapper.countChunksByStatus(fileId, FileChunkStatus.INIT.getCode());
    }

    /**
     * 合并所有分片
     *
     * @param fileId 关联的node.id
     * @throws IOException IO异常
     */
    private void mergeChunks(Long fileId) throws IOException {
        Node node = nodeMapper.selectNodeById(fileId);
        if (node == null || !Objects.equals(ObjectTypeEnum.CHUNK_INTERM.getCode(), node.getType())) {
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
        updateNode.setType(ObjectTypeEnum.FILE.getCode());
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
    }

    /*
        辅助方法
         */
    private ResolvedPath parsePath(String path) {
        if ("/".equals(path)) {
            throw new IllegalArgumentException("Cannot parse root directory '/' to get parent.");
        }

        int lastSlashIndex = path.lastIndexOf('/');

        // case: /file.txt -> parent: /, name: file.txt
        if (lastSlashIndex == 0) {
            return new ResolvedPath("/", path.substring(1));
        }

        // case: /home/user/file.txt -> parent: /home/user, name: file.txt
        String parentPath = path.substring(0, lastSlashIndex);
        String name = path.substring(lastSlashIndex + 1);
        return new ResolvedPath(parentPath, name);
    }

    @Data
    @AllArgsConstructor
    private static class ResolvedPath {
        private String parentPath;
        private String name;
    }

    @Data
    @AllArgsConstructor
    private static class SearchQueueItem {
        final Long nodeId; // null for root
        final String path;
    }
}
