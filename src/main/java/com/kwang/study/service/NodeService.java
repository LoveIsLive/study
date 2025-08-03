package com.kwang.study.service;

import com.kwang.study.cache.NodeCache;
import com.kwang.study.constant.FileStorageConstant;
import com.kwang.study.configuration.AppConfig;
import com.kwang.study.dto.NodeDetailDTO;
import com.kwang.study.dto.result.CDDirResult;
import com.kwang.study.enums.NodeTypeEnum;
import com.kwang.study.enums.PermissionsEnum;
import com.kwang.study.exception.NodeNotFoundException;
import com.kwang.study.filesystem.FileStorage;
import com.kwang.study.mapper.HashRefNumMapper;
import com.kwang.study.mapper.NodeMapper;
import com.kwang.study.pojo.HashRefNum;
import com.kwang.study.pojo.Node;
import com.kwang.study.utils.ChunkUtil;
import com.kwang.study.utils.HashUtil;
import com.kwang.study.utils.TextMimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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

    @Autowired
    private HashRefNumMapper hashRefNumMapper;

    @Autowired
    private MimeTypeService mimeTypeService;

    @Autowired
    private AppConfig appConfig;

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

        int chunkSize = appConfig.getFileStorage().getChunkSize();
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

        Integer mimeTypeId = mimeTypeService.getOrCreateMimeTypeId(mimeTypeName);

        Node node = new Node();
        node.setParentId(parentId);
        node.setName(name);
        node.setType(NodeTypeEnum.FILE.getCode());
        node.setPermissions(permissions != null ? permissions : PermissionsEnum.ALL.getCode());
        node.setSize((long) readSize);
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
        if (Objects.equals(node.getType(), NodeTypeEnum.DIR.getCode())) {
            throw new IllegalArgumentException("Node is a dir.");
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
        List<String> nodeKeys = allDeleteNodes.stream()
                .map(n -> NodeCache.NODE_KEY_PREFIX + n.getId())
                .filter(StringUtils::hasText).collect(Collectors.toList());
        List<String> dirKeys = allDeleteNodes.stream()
                .map(n -> Objects.equals(n.getType(), NodeTypeEnum.DIR.getCode()) ? NodeCache.CHILDREN_KEY_PREFIX + n.getId() : "")
                .filter(StringUtils::hasText).collect(Collectors.toList());
        ArrayList<String> keys = new ArrayList<>(nodeKeys);
        keys.addAll(dirKeys);
        nodeCache.batchDeleteCache(keys);
        // 删除父级缓存
        invalidateParentCache(node.getParentId());
    }

    /**
     * 列出指定目录的详细内容
     */
    public List<NodeDetailDTO> listDirectoryDetailContents(Long parentId) {
        validateParent(parentId);
        List<NodeDetailDTO> children = nodeCache.getChildrenCache(parentId);
        if (children == null) {
            children = nodeMapper.selectChildrenDetailByParentId(parentId, FileStorageConstant.ALL_FILE_TYPE);
            nodeCache.setChildrenCache(parentId, children);
        }
        return children;
    }

    /**
     * 重命名节点（文件或目录）
     * @param id 节点ID
     * @param newName 新名称
     * @return 更新后的节点信息
     */
    @Transactional
    public Node renameNode(Long id, String newName) {
        // 1. 检查节点是否存在
        Node existingNode = this.getNodeById(id);
        if (existingNode == null) {
            throw new NodeNotFoundException("节点不存在, id: " + id);
        }

        // 3. 更新名称
        nodeMapper.updateNodeParentAndName(id, null, newName);

        invalidateParentCache(existingNode.getParentId());

        Node node = new Node();
        BeanUtils.copyProperties(existingNode, node);
        return node;
    }

    /**
     * 获取节点的详细信息（包含MIME类型）
     * @param id 节点ID
     * @return 节点详细信息DTO
     */
    public NodeDetailDTO getNodeDetails(Long id) {
        NodeDetailDTO nodeDetail = nodeMapper.selectNodeDetailById(id);
        if (nodeDetail == null) {
            throw new NodeNotFoundException("节点不存在, id: " + id);
        }
        return nodeDetail;
    }

    /**
     * 在指定目录下递归模糊搜索节点，并将结果（包含完整路径）通过回调函数流式返回。
     * 使用广度优先搜索（BFS）以减少数据库压力，并在遍历时构建路径。
     *
     * @param startNodeId 起始搜索的目录ID，如果为null，则从根目录开始。
     * @param namePattern 搜索的名称模式（Java String.contains()的逻辑）
     * @param resultConsumer 用于处理找到的每个匹配节点的回调函数
     */
    @Transactional
    public void searchNodesBFS(Long startNodeId, String namePattern, Consumer<NodeDetailDTO> resultConsumer) {
        Queue<SearchQueueItem> directoryQueue = new LinkedList<>();

        // 初始化队列
        if (startNodeId == null) {
            directoryQueue.add(new SearchQueueItem(null, "/"));
        } else {
            // 如果从子目录开始，需要先获取其完整路径
            String startPath = nodeMapper.selectFullPathById(startNodeId);
            if (startPath == null) {
                throw new NodeNotFoundException("起始搜索目录不存在, id: " + startNodeId);
            }
            directoryQueue.add(new SearchQueueItem(startNodeId, startPath));
        }

        String lowerCasePattern = namePattern.toLowerCase();

        while (!directoryQueue.isEmpty()) {
            SearchQueueItem current = directoryQueue.poll();

            List<NodeDetailDTO> children = this.listDirectoryDetailContents(current.nodeId);

            if (CollectionUtils.isEmpty(children)) {
                continue;
            }

            for (NodeDetailDTO child : children) {
                // 安全地构建子节点的完整路径
                String childFullPath = current.path + child.getName() + "/";
                child.setFullPath(childFullPath); // 设置完整路径到DTO中

                // 检查名称是否匹配
                if (child.getName().toLowerCase().contains(lowerCasePattern)) {
                    NodeDetailDTO item = new NodeDetailDTO();
                    BeanUtils.copyProperties(child, item);
                    item.setFullPath(item.getFullPath().substring(0, item.getFullPath().length() - 1));
                    resultConsumer.accept(item);
                }

                // 如果是目录，则加入队列以便继续搜索其子目录
                if (Objects.equals(child.getType(), NodeTypeEnum.DIR.getCode())) {
                    directoryQueue.add(new SearchQueueItem(child.getId(), childFullPath));
                }
            }
        }
    }

    /**
     * 根据路径名列出目标目录的内容，支持绝对路径和相对路径。
     * 类似于 `cd <path>` 然后 `ls` 的组合命令。
     *
     * @param path 路径字符串 (e.g., "/home/user", "documents", "../project")
     * @param currentDirectoryId 当前目录的ID。当路径为相对路径时使用，绝对路径则忽略此参数。
     * @return 目标目录下的节点列表 (List<Node>)
     */
    @Transactional
    public CDDirResult listDirectoryByPath(String path, Long currentDirectoryId) {
        CDDirResult cdDirResult = new CDDirResult();

        Long targetDirectoryId = null;
        if (StringUtils.hasText(path)) {
            // 如果路径是绝对路径
            if (path.startsWith("/") || currentDirectoryId == null) {
                if (currentDirectoryId == null && !path.startsWith("/")) {
                    path = "/" + path;
                }
                targetDirectoryId = findNodeIdByAbsolutePath(path);
            } else { // 如果路径是相对路径
                targetDirectoryId = findNodeIdByRelativePath(path, currentDirectoryId);
            }
        }


        // 根据找到的目标目录ID，查询其内容
        cdDirResult.setDirId(targetDirectoryId);
        cdDirResult.setDirPath(nodeMapper.selectFullPathById(targetDirectoryId));
        cdDirResult.setNodeDetailDTOS(this.listDirectoryDetailContents(targetDirectoryId));
        return cdDirResult;
    }

    /**
     * 辅助方法：通过绝对路径查找节点ID
     */
    private Long findNodeIdByAbsolutePath(String path) {
        if ("/".equals(path)) {
            return null; // `null` ID 代表根目录
        }

        // 去掉首部'/'并按'/'分割
        String[] parts = path.substring(1).split("/");
        Long currentParentId = null; // 从根目录开始
        Node currentNode = null;

        for (String part : parts) {
            if (!StringUtils.hasText(path) || ".".equals(part)) {
                continue; // 忽略空或当前目录指示符
            }
            currentNode = nodeMapper.selectNodeByParentIdAndName(currentParentId, part);
            if (currentNode == null) {
                throw new NodeNotFoundException("路径不存在: " + path);
            }
            currentParentId = currentNode.getId();
        }

        // 验证最终找到的节点是目录
        if (currentNode != null && !Objects.equals(currentNode.getType(), NodeTypeEnum.DIR.getCode())) {
            throw new IllegalArgumentException("路径指向一个文件，而不是目录: " + path);
        }

        return currentNode != null ? currentNode.getId() : null;
    }

    /**
     * 辅助方法：通过相对路径查找节点ID
     */
    private Long findNodeIdByRelativePath(String path, Long startDirId) {
        String[] parts = path.split("/");
        Node currentNode = this.validateParent(startDirId);
        if (currentNode == null) {
            throw new IllegalArgumentException("当前目录不存在, id: " + startDirId);
        }
        if (!Objects.equals(currentNode.getType(), NodeTypeEnum.DIR.getCode())) {
            throw new IllegalArgumentException("当前节点不是目录,无法从此开始相对路径查找, id: " + startDirId);
        }


        for (String part : parts) {
            if (!StringUtils.hasText(path) || ".".equals(part)) {
                continue; // 忽略空或当前目录指示符
            }
            if ("..".equals(part) && currentNode != null) {
                // 回到上一级
                currentNode = (currentNode.getParentId() == null) ? null : this.getNodeById(currentNode.getParentId());
            } else {
                Long parentId = (currentNode == null) ? null : currentNode.getId();
                currentNode = nodeMapper.selectNodeByParentIdAndName(parentId, part);
                if (currentNode == null) {
                    throw new NodeNotFoundException("路径不存在: " + path);
                }
            }
        }

        // 验证最终找到的节点是目录
        if (currentNode != null && !Objects.equals(currentNode.getType(), NodeTypeEnum.DIR.getCode())) {
            throw new IllegalArgumentException("路径指向一个文件，而不是目录: " + path);
        }

        return (currentNode == null) ? null : currentNode.getId();
    }

    /**
     * 下载文件（支持断点续传）
     *
     * @param fileId   文件ID
     * @param request  HTTP请求
     * @param response HTTP响应
     * @throws IOException IO异常
     */
    public void downloadFile(Long fileId, String mode, HttpServletRequest request, HttpServletResponse response) throws IOException {
        NodeDetailDTO node = nodeMapper.selectNodeDetailById(fileId);
        if (node == null || !Objects.equals(NodeTypeEnum.FILE.getCode(), node.getType())) {
            response.setStatus(SC_NOT_FOUND);
            response.getWriter().write("File data not found in storage");
            return;
        }

        HashRefNum hashRefNum = node.getHashId() != null ? hashRefNumMapper.selectById(node.getHashId()) : null;
        if (hashRefNum == null) {
            response.setStatus(SC_NOT_FOUND);
            response.getWriter().write("File data not found in storage");
            return;
        }

        String fileKey = hashRefNum.getRefPath();

        try (InputStream is = fileStorage.getFile(fileKey)) {
            if (is == null) {
                response.setStatus(SC_NOT_FOUND);
                response.getWriter().write("File data not found in storage");
                return;
            }

            long fileSize = node.getSize();
            // 处理Range请求
            long[] range = parseRangeHeader(request, fileSize);
            long start = range[0];
            long end = range[1];
            long length = end - start + 1;

            String mimeTypeName = node.getMimeTypeName();
            String contentType = mimeTypeName != null ? mimeTypeName : "application/octet-stream";

            // 如果是文本类型的文件，明确指定UTF-8编码
            if (TextMimeUtil.isTextBased(mimeTypeName)) {
                contentType += "; charset=UTF-8";
            }
            response.setContentType(contentType);
            String encodedFileName = URLEncoder.encode(node.getName(), StandardCharsets.UTF_8).replace("+", "%20");
            String dispositionType = "inline".equals(mode) ? "inline" : "attachment";
            response.setHeader("Content-Disposition", dispositionType + "; filename=\"" + encodedFileName + "\"; filename*=UTF-8''" + encodedFileName);
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
     * 辅助方法：校验父节点是否是有效目录
     */
    public Node validateParent(Long parentId) {
        if (parentId != null) {
            Node parent = this.getNodeById(parentId);
            if (parent == null || !Objects.equals(parent.getType(), NodeTypeEnum.DIR.getCode())) {
                throw new IllegalArgumentException("Parent not found or is not a directory.");
            }
            return parent;
        }
        return null;
    }

    /**
     * 辅助方法：使父目录的缓存失效
     */
    private void invalidateParentCache(Long parentId) {
        nodeCache.deleteChildrenCache(parentId);
    }

    private static class SearchQueueItem {
        final Long nodeId; // null for root
        final String path;

        SearchQueueItem(Long nodeId, String path) {
            this.nodeId = nodeId;
            this.path = path;
        }
    }
}