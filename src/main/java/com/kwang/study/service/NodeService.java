package com.kwang.study.service;

import com.kwang.study.cache.NodeCache;
import com.kwang.study.enums.NodeTypeEnum;
import com.kwang.study.enums.PermissionsEnum;
import com.kwang.study.filesystem.FileStorage;
import com.kwang.study.mapper.NodeMapper;
import com.kwang.study.pojo.Node;
import com.kwang.study.utils.HashUtil;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Log4j2
public class NodeService {

    @Autowired
    private NodeMapper nodeMapper;

    @Autowired
    private FileStorage fileStorage;

    @Autowired
    private NodeCache nodeCache;

    // TODO: service层统一返回类型
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
        nodeCache.deleteChildrenCache(parentId); // 删除父目录缓存
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
        nodeCache.deleteChildrenCache(parentId); // 删除父目录缓存
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
        LinkedList<Node> deleteNodes = new LinkedList<>();
        collectDeleteNodes(node, deleteNodes);
        List<Long> collect = deleteNodes.stream().map(Node::getId).collect(Collectors.toList());
        ExecutorService pool = Executors.newFixedThreadPool(3);
        pool.submit(() -> {
            // 批处理删除数据库node
            nodeMapper.deleteNodeByIds(collect);
        });
        pool.submit(() -> {
            // 删除文件
            for (Node deleteNode : deleteNodes) {
                if (deleteNode.getType() == NodeTypeEnum.FILE.getCode()) {
                    try {
                        fileStorage.deleteFile(deleteNode.getRefPath());
                    } catch (IOException e) {
                        log.error("删除文件{}-{}操作失败，{}", node.getId(), node.getName(), e.getMessage());
                    }
                }
            }
        });
        pool.submit(() -> {
            // 删除缓存
            deleteNodeCache(node);
            if (node.getParentId() == null) {
                nodeCache.deleteRootChildren();
            } else {
                nodeCache.deleteChildrenCache(node.getParentId());
            }
        });
        boolean flag = false;
        try {
            flag = pool.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.error("删除文件{}-{}操作失败，{}", node.getId(), node.getName(), e.getMessage());
        }
        if (!flag) {
            log.error("删除文件{}-{}操作失败，超时", node.getId(), node.getName());
        }
    }

    private void collectDeleteNodes(Node node, List<Node> result) {
        if (node == null) return;
        result.add(node);
        if (node.getType() == NodeTypeEnum.DIR.getCode()) {
            List<Node> children = this.listOrdinaryDirectoryContents(node.getId());
            for (Node child : children) {
                collectDeleteNodes(child, result);
            }
        }
    }

    // 以node为根递归删除节点缓存（注意不会删除node.parent）
    private void deleteNodeCache(Node node) {
        if (node.getType() == NodeTypeEnum.DIR.getCode()) {
            List<Node> children = this.listOrdinaryDirectoryContents(node.getId());
            for (Node child : children) {
                deleteNodeCache(child);
            }
            nodeCache.deleteChildrenCache(node.getId());
        } else {
            nodeCache.deleteNodeCache(node.getId());
        }
    }


    public void updateFileContent(Long id, InputStream newFile) throws Exception {
        Node node = this.getNodeById(id);
        if (node == null || node.getType() != NodeTypeEnum.FILE.getCode()) {
            throw new IllegalArgumentException("Invalid file node");
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = newFile.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
        byte[] contentBytes = outputStream.toByteArray();

        String newHash = HashUtil.sha256Hash(contentBytes);
        String key = node.getRefPath();

        fileStorage.putFile(key, new ByteArrayInputStream(contentBytes));

        nodeMapper.updateNodeForFile(id, key, newHash, contentBytes.length);
        nodeCache.deleteNodeCache(id);
    }

    public List<Node> listRootDirectoryContents() {
        List<Node> rootChildren = nodeCache.getRootChildren();
        if (rootChildren == null) {
            rootChildren =  nodeMapper.selectRootChildren();
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
            children = nodeMapper.selectChildrenByParentId(parentId);
            nodeCache.setChildrenCache(parentId, children);
        }
        return children;
    }

    // 辅助方法
    private void validateParent(Long parentId) {
        if (parentId != null) {
            Node parent = this.getNodeById(parentId);
            if (parent != null && parent.getType() != NodeTypeEnum.DIR.getCode()) {
                throw new IllegalArgumentException("Parent must be a valid directory");
            }
        }
    }

    private void checkNameUnique(Long parentId, String name) {
        if (nodeMapper.selectNodeByParentIdAndName(parentId, name) != null) {
            throw new IllegalArgumentException("Name must be unique in the directory");
        }
    }
}