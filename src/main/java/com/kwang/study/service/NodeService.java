package com.kwang.study.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.kwang.study.cache.NodeCache;
import com.kwang.study.common.FileStorageConstant;
import com.kwang.study.enums.NodeTypeEnum;
import com.kwang.study.enums.PermissionsEnum;
import com.kwang.study.exception.NodeNotFoundException;
import com.kwang.study.filesystem.FileStorage;
import com.kwang.study.mapper.HashRefNumMapper;
import com.kwang.study.mapper.MimeTypeMapper;
import com.kwang.study.mapper.NodeMapper;
import com.kwang.study.pojo.HashRefNum;
import com.kwang.study.pojo.MimeType;
import com.kwang.study.pojo.Node;
import com.kwang.study.utils.HashUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;


@Service
@Slf4j
public class NodeService {

    @Autowired
    private NodeMapper nodeMapper;

    @Autowired
    @Qualifier("fileStorage")
    private FileStorage fileStorage;

    @Autowired
    private NodeCache nodeCache;

    @Autowired
    private HashRefNumMapper hashRefNumMapper;

    @Autowired
    private MimeTypeService mimeTypeService;

    /**
     * 根据ID获取文件输入流
     */
    public InputStream getFileById(Long id) throws IOException {
        Node node = this.getNodeById(id);
        if (node == null || !Objects.equals(node.getType(), NodeTypeEnum.FILE.getCode())) {
            throw new NodeNotFoundException("File not found or node is not a file.");
        }
        if (node.getHashId() == null) {
            // 处理空文件的情况
            return new ByteArrayInputStream(new byte[0]);
        }
        HashRefNum hashRefNum = hashRefNumMapper.selectById(node.getHashId());
        if (hashRefNum == null) {
            throw new NodeNotFoundException("File physical data not found.");
        }

        return fileStorage.getFile(hashRefNum.getRefPath());
    }

    /**
     * 根据ID获取节点信息（优先走缓存）
     */
    public Node getNodeById(Long id) {
        if (id == null) {
            return null;
        }
        Node node = nodeCache.getNodeCache(id);
        if (node == null) {
            node = nodeMapper.selectNodeById(id);
            if (node != null) {
                nodeCache.setNodeCache(id, node);
            }
        }
        return node;
    }

    /**
     * 创建目录
     */
    @Transactional
    public Node createDirectory(String name, Long parentId, String permissions) {
        validateParent(parentId);

        Node node = new Node();
        node.setParentId(parentId);
        node.setName(name);
        node.setType(NodeTypeEnum.DIR.getCode());
        node.setPermissions(permissions != null ? permissions : PermissionsEnum.ALL.getCode());
        node.setSize(0L);
        // 目录没有hashId和mimeType
        node.setHashId(null);
        node.setMimeTypeId(null);

        nodeMapper.insertNode(node);

        // 更新缓存
        invalidateParentCache(parentId);
        return node;
    }

    /**
     * 创建文件（处理文件去重）
     * 注意：仅仅小文件可以调用这个方法，不会出现OOM
     */
    @Transactional
    public Node createFile(String name, Long parentId, InputStream fileStream, String permissions, String mimeTypeName) throws IOException {
        validateParent(parentId);
        if (!StringUtils.hasText(mimeTypeName)) {
            throw new IllegalArgumentException("mimeTypeName cannot is null");
        }

        byte[] content = fileStream.readAllBytes();
        String hash = HashUtil.sha256Hash(content);

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
            hashRefNum.setSize((long) content.length);
            hashRefNumMapper.insertHash(hashRefNum);
        }

        Integer mimeTypeId = mimeTypeService.getOrCreateMimeTypeId(mimeTypeName);

        Node node = new Node();
        node.setParentId(parentId);
        node.setName(name);
        node.setType(NodeTypeEnum.FILE.getCode());
        node.setPermissions(permissions != null ? permissions : PermissionsEnum.ALL.getCode());
        node.setSize((long) content.length);
        node.setHashId(hashRefNum.getId());
        node.setMimeTypeId(mimeTypeId);

        nodeMapper.insertNode(node);

        // 更新缓存
        invalidateParentCache(parentId);
        return node;
    }

    /**
     * 删除文件节点（带引用计数处理）
     */
    @Transactional
    public void deleteFileNode(Long id) throws IOException {
        Node node = this.getNodeById(id);
        if (node == null) return;
        if (!Objects.equals(node.getType(), NodeTypeEnum.FILE.getCode())) {
            throw new IllegalArgumentException("Node is not a file.");
        }

        // 1. 删除节点记录
        nodeMapper.deleteNodeById(id);

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

        // 3. 删除缓存
        nodeCache.deleteNodeCache(id);
        invalidateParentCache(node.getParentId());
    }

    /**
     * 递归删除目录节点（危险操作！！！）
     */
    @Transactional
    public void deleteDirNode(Long id) throws IOException {
        Node node = this.getNodeById(id);
        if (node == null) return;
        if (!Objects.equals(node.getType(), NodeTypeEnum.DIR.getCode())) {
            throw new IllegalArgumentException("Node is not a directory.");
        }

        // 1. 获取所有后代节点ID
        List<Long> descendantIds = nodeMapper.selectAllDescendantIds(id);
        // 加上自身ID
        descendantIds.add(id);

        // 2. 批量查询所有后代中的文件节点，以处理其哈希引用
        List<Node> allDeleteNodes = nodeMapper.selectNodesByIds(descendantIds);
        List<Node> allFileDeleteNodes = allDeleteNodes.stream()
                .filter(n -> Objects.equals(n.getType(), NodeTypeEnum.FILE.getCode()))
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
        nodeMapper.batchDeleteNodeByIds(allDeleteNodes.stream()
                .map(Node::getId).collect(Collectors.toList()));

        // 5. 删除缓存
        List<String> keys = allDeleteNodes.stream().map(n -> {
            if (Objects.equals(n.getType(), NodeTypeEnum.FILE.getCode())) {
                return NodeCache.NODE_KEY_PREFIX + n.getId();
            } else if (Objects.equals(n.getType(), NodeTypeEnum.DIR.getCode())) {
                return NodeCache.CHILDREN_KEY_PREFIX + n.getId();
            }
            return "";
        }).filter(StringUtils::hasText).collect(Collectors.toList());
        nodeCache.batchDeleteCache(keys);
        // 删除父级缓存
        invalidateParentCache(node.getParentId());
    }


    /**
     * 列出根目录内容
     */
    public List<Node> listRootDirectoryContents() {
        List<Node> rootChildren = nodeCache.getRootChildren();
        if (rootChildren == null) {
            rootChildren = nodeMapper.selectRootChildren(FileStorageConstant.COMMON_FILE_TYPE);
            nodeCache.setRootChildren(rootChildren);
        }
        return rootChildren;
    }

    /**
     * 列出指定目录内容
     */
    public List<Node> listOrdinaryDirectoryContents(Long parentId) {
        if (parentId == null) {
            throw new IllegalArgumentException("Parent ID must not be null for an ordinary directory.");
        }
        validateParent(parentId);
        List<Node> children = nodeCache.getChildrenCache(parentId);
        if (children == null) {
            children = nodeMapper.selectChildrenByParentId(parentId, FileStorageConstant.COMMON_FILE_TYPE);
            nodeCache.setChildrenCache(parentId, children);
        }
        return children;
    }

    /**
     * 检查文件哈希是否存在（用于秒传）
     */
    public boolean checkHash(String hash) {
        return hashRefNumMapper.selectByHash(hash) != null;
    }

    /**
     * 通过已存在的哈希创建文件节点（秒传）
     */
    @Transactional
    public Node existFileInsert(Long parentId, String name, String permissions, String hash, String mimeTypeName) {
        validateParent(parentId);
        if (!StringUtils.hasText(mimeTypeName)) {
            throw new IllegalArgumentException("mimeTypeName cannot is null");
        }

        HashRefNum hashRefNum = hashRefNumMapper.selectByHashForUpdate(hash);
        if (hashRefNum == null) {
            throw new NodeNotFoundException("Hash does not exist. Cannot use existFileInsert.");
        }

        // 增加引用计数
        hashRefNumMapper.incrementRefNum(hashRefNum.getId());

        Integer mimeTypeId = mimeTypeService.getOrCreateMimeTypeId(mimeTypeName);

        Node node = new Node();
        node.setParentId(parentId);
        node.setName(name);
        node.setType(NodeTypeEnum.FILE.getCode());
        node.setPermissions(permissions != null ? permissions : PermissionsEnum.ALL.getCode());
        node.setSize(hashRefNum.getSize()); // 从hash记录获取大小
        node.setHashId(hashRefNum.getId());
        node.setMimeTypeId(mimeTypeId);

        nodeMapper.insertNode(node);

        // 更新缓存
        invalidateParentCache(parentId);
        return node;
    }

    /**
     * 辅助方法：校验父节点是否是有效目录
     */
    public void validateParent(Long parentId) {
        if (parentId != null) {
            Node parent = this.getNodeById(parentId);
            if (parent == null || !Objects.equals(parent.getType(), NodeTypeEnum.DIR.getCode())) {
                throw new IllegalArgumentException("Parent not found or is not a directory.");
            }
        }
    }

    /**
     * 辅助方法：使父目录的缓存失效
     */
    private void invalidateParentCache(Long parentId) {
        if (parentId == null) {
            nodeCache.deleteRootChildren();
        } else {
            nodeCache.deleteChildrenCache(parentId);
        }
    }
}