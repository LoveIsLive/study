package com.kwang.study.service;

import com.kwang.study.cache.NodeCache;
import com.kwang.study.enums.NodeTypeEnum;
import com.kwang.study.enums.PermissionsEnum;
import com.kwang.study.exception.NodeNotFoundException;
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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.kwang.study.common.FileStorageConstant.COMMON_FILE_TYPE;
import static com.kwang.study.utils.HashUtil.bytesToHex;
import static com.kwang.study.utils.HashUtil.md;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_PARTIAL_CONTENT;

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

    public InputStream getFileById(Long id) throws IOException {
        Node node = this.getNodeById(id);
        if (node == null || !Objects.equals(node.getType(), NodeTypeEnum.FILE.getCode())) {
            throw new NodeNotFoundException();
        }

        return fileStorage.getFile(node.getRefPath());
    }

    public Node getNodeById(Long id) {
        Node node = nodeCache.getNodeCache(id);
        if (node == null) {
            node = nodeMapper.selectNodeById(id);
            nodeCache.setNodeCache(id, node);
        }
        return node;
    }

    // 创建目录
    public Node createDirectory(String name, Long parentId, String permissions) {
        validateParent(parentId);
        checkNameUnique(parentId, name);

        Node node = new Node();
        node.setParentId(parentId);
        node.setName(name);
        node.setType(NodeTypeEnum.DIR.getCode());
        node.setPermissions(permissions != null ? permissions : PermissionsEnum.ALL.getCode());
        node.setSize(0);
        node.setRefPath("");
        node.setHash("");

        nodeMapper.insertNode(node);
        if (node.getParentId() == null) {
            nodeCache.deleteRootChildren();
        } else {
            nodeCache.deleteChildrenCache(node.getParentId());
        }
        return node;
    }

    // 创建文件
    public Node createFile(String name, Long parentId, InputStream file, String permissions)
            throws Exception {
        validateParent(parentId);
        checkNameUnique(parentId, name);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = file.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
        byte[] contentBytes = outputStream.toByteArray();

        String hash = HashUtil.sha256Hash(contentBytes);
        String key = UUID.randomUUID().toString();

        fileStorage.putFile(key, new ByteArrayInputStream(contentBytes));

        Node node = new Node();
        node.setParentId(parentId);
        node.setName(name);
        node.setType(NodeTypeEnum.FILE.getCode());
        node.setPermissions(permissions != null ? permissions : PermissionsEnum.ALL.getCode());
        node.setSize(contentBytes.length);
        node.setRefPath(key);
        node.setHash(hash);

        try {
            nodeMapper.insertNode(node);
        } catch (Exception e) {
            // 插入失败时清理存储文件
            fileStorage.deleteFile(key);
            throw e;
        }
        if (node.getParentId() == null) {
            nodeCache.deleteRootChildren();
        } else {
            nodeCache.deleteChildrenCache(node.getParentId());
        }
        return node;
    }


    public void deleteFileNode(Long id) throws IOException {
        Node node = this.getNodeById(id);
        if (node == null) return;
        fileStorage.deleteFile(node.getRefPath());
        nodeMapper.deleteNodeById(id);
        nodeCache.deleteNodeCache(id);
        if (node.getParentId() != null) {
            nodeCache.deleteChildrenCache(node.getParentId());
        } else {
            nodeCache.deleteRootChildren();
        }
    }

    // 危险操作！！！
    public void deleteDirNode(Long id) throws IOException {
        Node node = this.getNodeById(id);
        if (node == null) return;
        ArrayList<Node> deleteNodes = new ArrayList<>();
        collectDeleteNodes(node, deleteNodes);
        List<Long> collect = deleteNodes.stream().map(Node::getId).collect(Collectors.toList());
        nodeMapper.batchDeleteNodeByIds(collect);
        // 删除文件
        new Thread(() -> {
            for (Node deleteNode : deleteNodes) {
                if (Objects.equals(deleteNode.getType(), NodeTypeEnum.FILE.getCode())) {
                    try {
                        fileStorage.deleteFile(deleteNode.getRefPath());
                    } catch (IOException e) {
                        log.error("删除文件{}-{}操作失败，{}", node.getId(), node.getName(), e.getMessage());
                    }
                }
            }
        }).start();
        // 删除缓存
        nodeCache.batchDeleteNodeCache(deleteNodes);
        if (node.getParentId() == null) {
            nodeCache.deleteRootChildren();
        } else {
            nodeCache.deleteChildrenCache(node.getParentId());
        }
    }

    private void collectDeleteNodes(Node node, List<Node> result) {
        if (node == null) return;
        result.add(node);
        if (Objects.equals(node.getType(), NodeTypeEnum.DIR.getCode())) {
            List<Node> children = this.listOrdinaryDirectoryContents(node.getId());
            for (Node child : children) {
                collectDeleteNodes(child, result);
            }
        }
    }

    public List<Node> listRootDirectoryContents() {
        List<Node> rootChildren = nodeCache.getRootChildren();
        if (rootChildren == null) {
            rootChildren =  nodeMapper.selectRootChildren(COMMON_FILE_TYPE);
            nodeCache.setRootChildren(rootChildren);
        }
        return rootChildren;
    }

    public List<Node> listOrdinaryDirectoryContents(Long parentId) {
        if (parentId == null)
            throw new IllegalArgumentException("Ordinary Directory must not null");
        validateParent(parentId);
        List<Node> children = nodeCache.getChildrenCache(parentId);
        if (children == null) {
            children = nodeMapper.selectChildrenByParentId(parentId, COMMON_FILE_TYPE);
            nodeCache.setChildrenCache(parentId, children);
        }
        return children;
    }

    // 辅助方法
    public void validateParent(Long parentId) {
        if (parentId != null) {
            Node parent = this.getNodeById(parentId);
            if (parent != null && !Objects.equals(parent.getType(), NodeTypeEnum.DIR.getCode())) {
                throw new IllegalArgumentException("Parent must be a valid directory");
            }
        }
    }

    public void checkNameUnique(Long parentId, String name) {
        if (nodeMapper.selectNodeByParentIdAndName(parentId, name) != null) {
            throw new IllegalArgumentException("Name must be unique in the directory");
        }
    }
}