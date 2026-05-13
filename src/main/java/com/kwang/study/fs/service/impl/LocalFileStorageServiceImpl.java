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
import com.kwang.study.fs.service.async.AsyncCleanupFileService;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StreamUtils;
import org.springframework.util.unit.DataSize;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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

    @Autowired
    private AsyncCleanupFileService cleanupFileService;

    private final ConcurrentHashMap<Long, Object> mapLock = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Long> uploadIdToFileId = new ConcurrentHashMap<>();

    @Override
    @Transactional
    public VoidResult createDirectory(String path) {
        if (!isOrdinaryPath(path))
            throw new InvalidPathException(path);
        ResolvedPath resolvedPath = parsePath(path);

        Node parentNode = null;
        if (!"/".equals(resolvedPath.parentPath)) {
            parentNode = nodeMapper.selectNodeByPath(resolvedPath.getParentPath());
            if (parentNode == null) {
                throw new PathNotFoundException(resolvedPath.getParentPath());
            }
            if (!Objects.equals(parentNode.getType(), ObjectTypeEnum.DIR.getCode())) {
                throw new NotADirectoryException(resolvedPath.getParentPath());
            }
        }

        if (nodeMapper.selectNodeByParentIdAndName(parentNode == null ? null : parentNode.getId(), resolvedPath.getName()) != null) {
            throw new PathAlreadyExistsException(path);
        }

        // 4. 创建新节点
        Node node = new Node();
        node.setParentId(parentNode == null ? null : parentNode.getId());
        node.setName(resolvedPath.getName());
        node.setType(ObjectTypeEnum.DIR.getCode());
        node.setIsHidden(0);
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
            throw new InvalidPathException(path);
        Integer mimeTypeId = mimeTypeService.getMimeTypeId(mimeTypeName);
        if (mimeTypeId == null) {
            throw new IllegalArgumentException("文件类型名称未知");
        }

        ResolvedPath resolvedPath = parsePath(path);
        Node parentNode = null;
        if (!"/".equals(resolvedPath.parentPath)) {
            parentNode = nodeMapper.selectNodeByPath(resolvedPath.getParentPath());
            if (parentNode == null) {
                throw new PathNotFoundException(resolvedPath.getParentPath());
            }
            if (!Objects.equals(parentNode.getType(), ObjectTypeEnum.DIR.getCode())) {
                throw new NotADirectoryException(resolvedPath.getParentPath());
            }
        }

        if (nodeMapper.selectNodeByParentIdAndName(parentNode == null ? null : parentNode.getId(), resolvedPath.getName()) != null) {
            throw new PathAlreadyExistsException(path);
        }

        // ================== ★ 核心优化区开始 ==================
        int maxChunkSize = fsConfig.getChunkSize(); // 最大限制 (如 10MB)
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        MessageDigest sha256 = HashUtil.sha256();

        byte[] buffer = new byte[8192]; // 仅开辟 8KB 的复用缓冲区
        int bytesRead;
        long totalRead = 0;

        // 边读流、边算Hash、边存入动态扩容内存
        while ((bytesRead = fileStream.read(buffer)) != -1) {
            totalRead += bytesRead;
            if (totalRead > maxChunkSize) {
                throw new IllegalArgumentException("上传文件大小超过小文件限制：" +
                        DataSize.ofBytes(maxChunkSize).toMegabytes() + "MB");
            }
            sha256.update(buffer, 0, bytesRead);
            baos.write(buffer, 0, bytesRead);
        }

        byte[] actualContent = baos.toByteArray(); // 获取精准大小的字节数组
        String hash = HashUtil.bytesToHex(sha256.digest());
        int finalSize = (int) totalRead;
        // ================== ★ 核心优化区结束 ==================

        boolean insertSuccess = true;
        String fileKey = UUID.randomUUID().toString();

        HashRefNum hashRefNum = new HashRefNum();
        hashRefNum.setHash(hash);
        hashRefNum.setRefPath(fileKey);
        hashRefNum.setRefNum(1);
        hashRefNum.setSize((long) finalSize);
        try {
            hashRefNumMapper.insertHash(hashRefNum);
        } catch (DuplicateKeyException e) {
            insertSuccess = false;
            log.info("插入失败-重复{}", e.getMessage());
            // 小心死锁
            hashRefNum = hashRefNumMapper.selectByHashForUpdate(hash);
            hashRefNumMapper.incrementRefNum(hashRefNum.getId());
        }
        if (insertSuccess) {
            // 直接传入实际大小的 actualContent，连 offset/len 都不需要截取了
            fileStorage.putFile(fileKey, new ByteArrayInputStream(actualContent));
        }

        Node node = new Node();
        node.setParentId(parentNode == null ? null : parentNode.getId());
        node.setName(resolvedPath.getName());
        node.setType(ObjectTypeEnum.FILE.getCode());
        node.setIsHidden(0);
        node.setSize((long) finalSize);
        node.setHashId(hashRefNum.getId());
        node.setMimeTypeId(mimeTypeId);

        nodeMapper.insertNode(node);

        return VoidResult.success();
    }

    @Override
    @Transactional
    public VoidResult deleteFileObject(String path) throws IOException {
        if (!isOrdinaryPath(path))
            throw new InvalidPathException(path);

        Node node = nodeMapper.selectNodeByPath(path);
        if (node == null) {
            throw new PathNotFoundException(path);
        }
        if (!Objects.equals(node.getType(), ObjectTypeEnum.FILE.getCode())) {
            throw new NotAFileException(path);
        }

        // 1. 删除节点记录
        nodeMapper.deleteNodeById(node.getId());

        // 2. 处理哈希引用计数
        if (node.getHashId() != null) {
            HashRefNum hashRefNum = hashRefNumMapper.selectByIdForUpdate(node.getHashId());
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
            throw new InvalidPathException(path);

        Node node = nodeMapper.selectNodeByPath(path);
        if (node == null) {
            throw new PathNotFoundException(path);
        }
        if (!Objects.equals(node.getType(), ObjectTypeEnum.DIR.getCode())) {
            throw new NotADirectoryException(path);
        }

        // 1. 获取所有后代节点ID
        List<Long> descendantIdResult = nodeMapper.selectAllDescendantIds(node.getId());
        List<Long> descendantIds = new ArrayList<>(descendantIdResult);
        // 加上自身ID
        descendantIds.add(node.getId());

        // 2. 批量查询所有后代中的文件节点，以处理其哈希引用
        List<Node> allDeleteNodes = nodeMapper.selectNodesByIds(descendantIds);
        List<Node> allFileDeleteNodes = allDeleteNodes.stream()
                .filter(n -> Objects.equals(n.getType(), ObjectTypeEnum.FILE.getCode()))
                .collect(Collectors.toList());

        // 3. 按hashId分组，高效处理引用计数
        Map<Long, Long> hashIdCounts = allFileDeleteNodes.stream()
                .filter(n -> n.getHashId() != null)
                .collect(Collectors.groupingBy(Node::getHashId, Collectors.counting()));

        ArrayList<String> keys = new ArrayList<>();

        for (Map.Entry<Long, Long> entry : hashIdCounts.entrySet()) {
            Long hashId = entry.getKey();
            long countToDelete = entry.getValue();

            HashRefNum lockedHashRef = hashRefNumMapper.selectByIdForUpdate(hashId);
            if (lockedHashRef != null) {
                if (lockedHashRef.getRefNum() <= countToDelete) {
                    keys.add(lockedHashRef.getRefPath());
                    hashRefNumMapper.deleteById(lockedHashRef.getId());
                } else {
                    hashRefNumMapper.batchDecrementRefNum(hashId, countToDelete);
                }
            }
        }

        // 异步删除物理文件
        cleanupFileService.cleanup(keys);

        // 4. 批量删除数据库中的所有节点（包括目录和已经被处理过的文件节点记录）
        nodeMapper.batchDeleteNodeByIds(descendantIds);

        return VoidResult.success();
    }

    @Override
    @Transactional
    public VoidResult updateFileObject(String path, String newName, InputStream fileStream, String mimeTypeName) throws IOException {
        if (!isOrdinaryPath(path))
            throw new InvalidPathException(path);
        if (newName == null && fileStream == null && mimeTypeName == null)
            return VoidResult.success();

        Integer mimeTypeId = null;
        if (mimeTypeName != null) {
            mimeTypeId = mimeTypeService.getMimeTypeId(mimeTypeName);
            if (mimeTypeId == null) {
                throw new IllegalArgumentException("文件类型名称未知");
            }
        }

        Node originalNode = nodeMapper.selectNodeByPath(path);
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
            // ================== ★ 核心优化区 ==================
            int maxChunkSize = fsConfig.getChunkSize();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MessageDigest sha256 = HashUtil.sha256();

            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalRead = 0;

            while ((bytesRead = fileStream.read(buffer)) != -1) {
                totalRead += bytesRead;
                if (totalRead > maxChunkSize) {
                    throw new IllegalArgumentException("上传文件大小超过限制：" +
                            DataSize.ofBytes(maxChunkSize).toMegabytes() + "MB");
                }
                sha256.update(buffer, 0, bytesRead);
                baos.write(buffer, 0, bytesRead);
            }

            byte[] actualContent = baos.toByteArray();
            String hash = HashUtil.bytesToHex(sha256.digest());
            int finalSize = (int) totalRead;
            // ==================================================

            HashRefNum hashRefNum = hashRefNumMapper.selectByHashForUpdate(hash);
            String fileKey;
            if (hashRefNum != null) {
                hashRefNumMapper.incrementRefNum(hashRefNum.getId());
            } else {
                fileKey = UUID.randomUUID().toString();
                // 存入精准大小的 actualContent
                fileStorage.putFile(fileKey, new ByteArrayInputStream(actualContent));

                hashRefNum = new HashRefNum();
                hashRefNum.setHash(hash);
                hashRefNum.setRefPath(fileKey);
                hashRefNum.setRefNum(1);
                hashRefNum.setSize((long) finalSize);
                hashRefNumMapper.insertHash(hashRefNum);
            }

            // 处理旧文件引用逻辑不变...
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
            node.setSize((long) finalSize);
        }

        nodeMapper.updateNode(node);

        return VoidResult.success();
    }

    @Override
    @Transactional
    public VoidResult updateDirObject(String path, String newName) {
        if (!isOrdinaryPath(path))
            throw new InvalidPathException(path);
        if (newName == null)
            return VoidResult.success();
        if (!isValidName(newName)) {
            throw new IllegalArgumentException("不合法的目录名称" + newName);
        }

        Node originalNode = nodeMapper.selectNodeByPath(path);
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
            throw new InvalidPathException(path);

        Node node = null;
        if (!"/".equals(path)) {
            node = nodeMapper.selectNodeByPath(path);
            if (node == null)
                throw new PathNotFoundException(path);
            if (!Objects.equals(node.getType(), ObjectTypeEnum.DIR.getCode())) {
                throw new NotADirectoryException(path);
            }
        }

        List<NodeDetail> children = nodeMapper.selectChildrenDetailByParentId(node == null ?
                null : node.getId());
        DirObjectResult result = new DirObjectResult();
        result.setSuccess(Boolean.TRUE);
        if (node != null) {
            BeanUtils.copyProperties(node, result);
        }
        result.setFileObjectDescs(children.stream().map(n -> {
            DirObjectResult.FileObjectDesc desc = new DirObjectResult.FileObjectDesc();
            BeanUtils.copyProperties(n, desc);
            return desc;
        }).collect(Collectors.toList()));
        return result;
    }

    @Override
    @Transactional
    public FileObjectResult getFileObject(String path) throws IOException {
        if (!isOrdinaryPath(path))
            throw new InvalidPathException(path);

        Node node = nodeMapper.selectNodeDetailByPath(path);
        if (node == null) {
            throw new PathNotFoundException(path);
        }
        if (!Objects.equals(node.getType(), ObjectTypeEnum.FILE.getCode())) {
            throw new NotAFileException(path);
        }
        FileObjectResult result = new FileObjectResult();
        BeanUtils.copyProperties(node, result);
        if (node.getHashId() != null) {
            HashRefNum hashRefNum = hashRefNumMapper.selectById(node.getHashId());
            if (hashRefNum != null) {
                result.setContent(fileStorage.getFile(hashRefNum.getRefPath()));
            }
        }
        result.setSuccess(Boolean.TRUE);
        return result;
    }

    @Override
    @Transactional
    public GenericObjectResult getObjectDesc(String path) throws IOException {
        if (!isOrdinaryPath(path))
            throw new InvalidPathException(path);

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
            throw new InvalidPathException(path);

        Integer mimeTypeId = mimeTypeService.getMimeTypeId(mimeTypeName);
        if (mimeTypeId == null) {
            throw new IllegalArgumentException("文件类型名称未知");
        }

        ResolvedPath resolvedPath = parsePath(path);
        Node parentNode = null;
        if (!"/".equals(resolvedPath.parentPath)) {
            parentNode = nodeMapper.selectNodeByPath(resolvedPath.parentPath);
            if (parentNode == null) {
                throw new PathNotFoundException(resolvedPath.parentPath);
            }
            if (!Objects.equals(parentNode.getType(), ObjectTypeEnum.DIR.getCode())) {
                throw new NotADirectoryException(resolvedPath.getParentPath());
            }
        }

        if (nodeMapper.selectNodeByParentIdAndName(parentNode == null ? null : parentNode.getId(), resolvedPath.getName()) != null) {
            throw new PathAlreadyExistsException(path);
        }

        Node node = new Node();
        node.setParentId(parentNode == null ? null : parentNode.getId());
        node.setName(resolvedPath.getName());
        // 设置为分块上传中间态
        node.setType(ObjectTypeEnum.CHUNK_INTERM.getCode());
        node.setIsHidden(0);
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
    public GenericObjectResult uploadChunk(String uploadId, Integer chunkIndex,
                                                     Integer totalChunks, InputStream chunkStream) throws IOException {
        if (chunkIndex == null || totalChunks == null || chunkIndex < 0 || chunkIndex >= totalChunks)
            throw new IllegalArgumentException("chunkIndex:" + chunkIndex + ", totalChunks:" + totalChunks);
        if (chunkStream == null)
            throw new IllegalArgumentException("输入流为空");
        Long fileId = uploadIdToFileId.get(uploadId);
        if (fileId == null)
            throw new IllegalArgumentException("uploadId不存在");

        GenericObjectResult result = new GenericObjectResult();
        this.uploadChunk(fileId, chunkIndex, chunkStream);
        result.setSuccess(Boolean.TRUE);
        return result;
    }

    @Override
    public GenericObjectResult mergeChunk(String uploadId, Integer totalChunks) throws IOException {
        if (totalChunks == null || totalChunks < 0)
            throw new IllegalArgumentException("totalChunks:" + totalChunks);
        Long fileId = uploadIdToFileId.get(uploadId);
        if (fileId == null)
            throw new IllegalArgumentException("uploadId不存在");
        int currentCount = this.countUploadedChunks(fileId);
        if (currentCount != totalChunks)
            throw new IllegalArgumentException("totalChunks:" + totalChunks + ", currentCount = " + currentCount);
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
        uploadIdToFileId.remove(uploadId); // Note
        GenericObjectResult result = new GenericObjectResult();
        result.setSuccess(Boolean.TRUE);
        return result;
    }

    @Override
    public VoidResult searchNodesBFS(String path, String namePattern, Consumer<SearchNodeResult> resultConsumer) {
        if (!isValidPath(path))
            throw new InvalidPathException(path);

        Node node = null;
        if (!"/".equals(path)) {
            node = nodeMapper.selectNodeByPath(path);
            if (node == null)
                throw new PathNotFoundException(path);
            if (!Objects.equals(node.getType(), ObjectTypeEnum.DIR.getCode())) {
                throw new NotADirectoryException(path);
            }
        }

        Queue<SearchQueueItem> directoryQueue = new LinkedList<>();
        Long startNodeId = node == null ? null : node.getId();
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

    @Override
    public MimeTypeIdResult getMimeTypeId(String mimeTypeName) {
        MimeTypeIdResult result = new MimeTypeIdResult();
        Integer mimeTypeId = mimeTypeService.getMimeTypeId(mimeTypeName);
        result.setSuccess(Boolean.TRUE);
        result.setMimeTypeId(mimeTypeId);
        return result;
    }

    @Override
    @Transactional
    public VoidResult updateNodeHiddenStatus(String path, Integer isHidden) throws IOException {
        if (!isOrdinaryPath(path)) throw new InvalidPathException(path);

        Node originalNode = nodeMapper.selectNodeByPath(path);
        if (originalNode == null) {
            throw new PathNotFoundException(path);
        }

        Node node = new Node();
        node.setId(originalNode.getId());
        node.setIsHidden(isHidden);

        nodeMapper.updateNode(node);
        return VoidResult.success();
    }

    @Override
    @Transactional
    public VoidResult archiveDirectory(String sourceDirPath, String destZipPath) throws IOException {
        Node sourceNode = nodeMapper.selectNodeByPath(sourceDirPath);
        if (sourceNode == null || !Objects.equals(sourceNode.getType(), ObjectTypeEnum.DIR.getCode())) {
            throw new NotADirectoryException(sourceDirPath);
        }

        ResolvedPath destPath = parsePath(destZipPath);
        Node destParentNode = nodeMapper.selectNodeByPath(destPath.getParentPath());
        if (destParentNode == null) throw new PathNotFoundException(destPath.getParentPath());
        if (nodeMapper.selectNodeByParentIdAndName(destParentNode.getId(), destPath.getName()) != null) {
            throw new PathAlreadyExistsException(destZipPath);
        }

        // 1. 创建服务器本地临时文件，防止内存溢出(OOM)
        File tempZipFile = File.createTempFile("archive-", ".zip");
        try {
            // 2. 递归遍历节点并写入 ZipOutputStream
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempZipFile))) {
                zipRecursive(sourceNode, "", zos);
            }

            // 3. 计算临时文件的 Hash 并存入底层文件系统
            long fileSize = tempZipFile.length();
            String hash;
            try (InputStream is = new FileInputStream(tempZipFile)) {
                // 使用现有的 HashUtil 对流进行摘要 (需要稍微改造或一次性读, 考虑到文件可能很大, 这里提供流式摘要思路)
                MessageDigest sha256 = HashUtil.sha256();
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    sha256.update(buffer, 0, bytesRead);
                }
                hash = HashUtil.bytesToHex(sha256.digest());
            }

            // 处理去重与物理存储
            HashRefNum hashRefNum = hashRefNumMapper.selectByHashForUpdate(hash);
            String fileKey;
            if (hashRefNum != null) {
                hashRefNumMapper.incrementRefNum(hashRefNum.getId());
            } else {
                fileKey = UUID.randomUUID().toString();
                try (InputStream is = new FileInputStream(tempZipFile)) {
                    fileStorage.putFile(fileKey, is);
                }
                hashRefNum = new HashRefNum();
                hashRefNum.setHash(hash);
                hashRefNum.setRefPath(fileKey);
                hashRefNum.setRefNum(1);
                hashRefNum.setSize(fileSize);
                hashRefNumMapper.insertHash(hashRefNum);
            }

            // 4. 插入节点数据 (获取 application/zip 的 MimeTypeId)
            Integer mimeTypeId = mimeTypeService.getMimeTypeId("application/zip");
            Node node = new Node();
            node.setParentId(destParentNode.getId());
            node.setName(destPath.getName());
            node.setType(ObjectTypeEnum.FILE.getCode());
            node.setIsHidden(0);
            node.setSize(fileSize);
            node.setHashId(hashRefNum.getId());
            node.setMimeTypeId(mimeTypeId == null ? 1 : mimeTypeId); // 兜底
            nodeMapper.insertNode(node);

        } finally {
            tempZipFile.delete(); // 清理临时文件
        }
        return VoidResult.success();
    }

    // 递归打包辅助方法 (修改版，免疫磁盘旧文件过大Bug)
    private void zipRecursive(Node dirNode, String basePath, ZipOutputStream zos) throws IOException {
        List<Node> children = nodeMapper.selectChildrenByParentId(dirNode.getId());
        for (Node child : children) {
            String entryName = basePath + child.getName();
            if (Objects.equals(child.getType(), ObjectTypeEnum.DIR.getCode())) {
                zos.putNextEntry(new ZipEntry(entryName + "/"));
                zos.closeEntry();
                zipRecursive(child, entryName + "/", zos);
            } else {
                zos.putNextEntry(new ZipEntry(entryName));
                if (child.getHashId() != null) {
                    HashRefNum hashRefNum = hashRefNumMapper.selectById(child.getHashId());
                    if (hashRefNum != null) {
                        try (InputStream is = fileStorage.getFile(hashRefNum.getRefPath())) {
                            // ============ ★ 核心拦截修复 ============
                            // 绝对不能用 StreamUtils.copy(is, zos) 直接拷贝整个流
                            // 必须严格按照数据库记录的真实大小 (child.getSize()) 读取，抛弃磁盘多余的 null 字节
                            long bytesToRead = child.getSize();
                            byte[] buffer = new byte[8192];
                            int read;
                            while (bytesToRead > 0 && (read = is.read(buffer, 0, (int) Math.min(buffer.length, bytesToRead))) != -1) {
                                zos.write(buffer, 0, read);
                                bytesToRead -= read;
                            }
                            // ========================================
                        }
                    }
                }
                zos.closeEntry();
            }
        }
    }

    @Override
    @Transactional
    public VoidResult unarchiveFile(String zipFilePath, String destDirPath) throws IOException {
        Node zipNode = nodeMapper.selectNodeByPath(zipFilePath);
        if (zipNode == null || !Objects.equals(zipNode.getType(), ObjectTypeEnum.FILE.getCode())) {
            throw new NotAFileException(zipFilePath);
        }
        Node destDirNode = nodeMapper.selectNodeByPath(destDirPath);
        if (destDirNode == null || !Objects.equals(destDirNode.getType(), ObjectTypeEnum.DIR.getCode())) {
            throw new NotADirectoryException(destDirPath);
        }

        HashRefNum zipHashRef = hashRefNumMapper.selectById(zipNode.getHashId());
        if (zipHashRef == null) return VoidResult.fail("压缩包底层文件丢失");

        // ================= ★ 核心改动 1：计算解压专属子目录 =================
        String zipName = zipNode.getName();
        // 如果是 测试.zip，提取出 "测试" 作为文件夹名
        String folderName = zipName.toLowerCase().endsWith(".zip") ?
                zipName.substring(0, zipName.length() - 4) : zipName + "_解压";

        // 生成目标根目录：destDirPath/测试
        String baseDestPath = destDirPath + (destDirPath.endsWith("/") ? "" : "/") + folderName;
        // =================================================================

        try (ZipInputStream zis = new ZipInputStream(fileStorage.getFile(zipHashRef.getRefPath()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();

                // 【安全校验】防御 Zip Slip 目录穿越漏洞
                if (entryName.contains("..")) continue;

                // ================= ★ 核心改动 2：将路径拼接到子目录下 =================
                String fullTargetPath = baseDestPath + "/" + entryName;
                // =================================================================

                ResolvedPath targetRes = parsePath(fullTargetPath);

                if (entry.isDirectory()) {
                    ensureDirExistsInternal(fullTargetPath);
                } else {
                    // 确保文件的父目录存在
                    Node parentNode = ensureDirExistsInternal(targetRes.getParentPath());

                    // 遇到文件，借用临时文件处理
                    File tempFile = File.createTempFile("unzip-", ".tmp");
                    try {
                        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                            // 这里只会读取当前ZIP Entry的数据，绝不会多读
                            StreamUtils.copy(zis, fos);
                        }

                        long fileSize = tempFile.length();
                        String hash;
                        try (InputStream is = new FileInputStream(tempFile)) {
                            MessageDigest sha256 = HashUtil.sha256();
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = is.read(buffer)) != -1) {
                                sha256.update(buffer, 0, bytesRead);
                            }
                            hash = HashUtil.bytesToHex(sha256.digest());
                        }

                        // 判重与插入逻辑
                        HashRefNum hashRefNum = hashRefNumMapper.selectByHashForUpdate(hash);
                        if (hashRefNum != null) {
                            hashRefNumMapper.incrementRefNum(hashRefNum.getId());
                        } else {
                            String fileKey = UUID.randomUUID().toString();
                            try (InputStream is = new FileInputStream(tempFile)) {
                                fileStorage.putFile(fileKey, is);
                            }
                            hashRefNum = new HashRefNum();
                            hashRefNum.setHash(hash);
                            hashRefNum.setRefPath(fileKey);
                            hashRefNum.setRefNum(1);
                            hashRefNum.setSize(fileSize);
                            hashRefNumMapper.insertHash(hashRefNum);
                        }

                        // 动态获取 MimeType
                        String mimeTypeName = java.nio.file.Files.probeContentType(new File(entryName).toPath());
                        if (mimeTypeName == null) mimeTypeName = "application/octet-stream";
                        Integer mimeTypeId = mimeTypeService.getMimeTypeId(mimeTypeName);

                        // 检查文件是否已存在，存在则覆盖，不存在则新增
                        Node existingFile = nodeMapper.selectNodeByParentIdAndName(parentNode.getId(), targetRes.getName());
                        if (existingFile != null) {
                            existingFile.setHashId(hashRefNum.getId());
                            existingFile.setSize(fileSize);
                            existingFile.setMimeTypeId(mimeTypeId == null ? 1 : mimeTypeId);
                            nodeMapper.updateNode(existingFile);
                        } else {
                            Node node = new Node();
                            node.setParentId(parentNode.getId());
                            node.setName(targetRes.getName());
                            node.setType(ObjectTypeEnum.FILE.getCode());
                            node.setIsHidden(0);
                            node.setSize(fileSize);
                            node.setHashId(hashRefNum.getId());
                            node.setMimeTypeId(mimeTypeId == null ? 1 : mimeTypeId);
                            nodeMapper.insertNode(node);
                        }
                    } finally {
                        tempFile.delete();
                    }
                }
                zis.closeEntry();
            }
        }
        return VoidResult.success();
    }

    // 内部辅助方法：确保多级目录存在，不存在则自动创建，返回叶子目录节点
    private Node ensureDirExistsInternal(String path) {
        if ("/".equals(path)) return null;
        Node node = nodeMapper.selectNodeByPath(path);
        if (node != null) return node;

        ResolvedPath res = parsePath(path);
        Node parentNode = ensureDirExistsInternal(res.getParentPath());

        Node newNode = new Node();
        newNode.setParentId(parentNode == null ? null : parentNode.getId());
        newNode.setName(res.getName());
        newNode.setType(ObjectTypeEnum.DIR.getCode());
        newNode.setIsHidden(0);
        newNode.setSize(0L);
        nodeMapper.insertNode(newNode);
        return newNode;
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
        int maxChunkSize = fsConfig.getChunkSize();
        if ((long) chunkIndex * maxChunkSize > DataSize.ofGigabytes(1).toBytes())
            throw new IllegalArgumentException("文件过大超过1GB");

        String chunkKey = fileId + "-" + chunkIndex;
        int actualReadSize = 0;

        // ================== ★ 极低内存流式直写 ==================
        // 不再 new byte[10MB]，而是直接打开目标文件的写入流
        try (OutputStream os = chunkStorage.openFile(chunkKey)) {
            byte[] buffer = new byte[8192]; // 仅 8KB 内存
            int bytesRead;
            while ((bytesRead = chunkStream.read(buffer)) != -1) {
                actualReadSize += bytesRead;
                if (actualReadSize > maxChunkSize) {
                    // 超限，立刻关闭流并清理坏文件
                    os.close();
                    chunkStorage.deleteFile(chunkKey);
                    throw new IllegalArgumentException("上传分片大小超过限制：" +
                            DataSize.ofBytes(maxChunkSize).toMegabytes() + "MB");
                }
                os.write(buffer, 0, bytesRead);
            }
        }
        // =========================================================

        FileChunk chunk = new FileChunk();
        chunk.setFileId(fileId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setKey(chunkKey);
        chunk.setStatus(FileChunkStatus.INIT.getCode());
        chunk.setSize(actualReadSize); // 填入实际读取的尺寸

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
                        if (totalSize > DataSize.ofGigabytes(1).toBytes()) {
                            throw new IllegalArgumentException("文件过大，超过1GB");
                        }
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
        updateNode.setIsHidden(0);
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
