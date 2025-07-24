package com.kwang.study.controller;

import com.kwang.study.common.R;
import com.kwang.study.dto.CreateDirectoryDTO;
import com.kwang.study.enums.NodeTypeEnum;
import com.kwang.study.exception.NodeNotFoundException;
import com.kwang.study.pojo.Node;
import com.kwang.study.service.NodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/nodes")
@Validated
@Slf4j
public class NodeController {

    private final NodeService nodeService;

    @Autowired
    public NodeController(NodeService nodeService) {
        this.nodeService = nodeService;
    }

    /**
     * 创建目录
     * @param request 包含目录名、父ID和权限的请求体
     * @return 包含新创建的目录节点的响应
     */
    @PostMapping("/directories")
    public ResponseEntity<R<Node>> createDirectory(@Valid @RequestBody CreateDirectoryDTO request) {
        Node node = nodeService.createDirectory(request.getName(), request.getParentId(), request.getPermissions());
        return ResponseEntity.status(201).body(R.success(node, "目录创建成功"));
    }

    /**
     * 列出目录内容
     * @param parentId 父目录ID。如果为null，则列出根目录内容。
     * @return 目录下的节点列表
     */
    @GetMapping
    public ResponseEntity<R<List<Node>>> listDirectoryContents(
            @RequestParam(required = false) Long parentId) {
        List<Node> nodes;
        if (parentId == null) {
            nodes = nodeService.listRootDirectoryContents();
        } else {
            nodes = nodeService.listOrdinaryDirectoryContents(parentId);
        }
        return ResponseEntity.ok(R.success(nodes));
    }

    /**
     * 上传小文件
     * @param name 文件名
     * @param parentId 父目录ID
     * @param file 上传的文件
     * @param permissions 权限字符串 (可选)
     * @param mimeTypeName 文件MIME类型
     * @return 包含新创建的文件节点的响应
     * @throws IOException IO异常
     */
    @PostMapping("/files")
    public ResponseEntity<R<Node>> uploadFile(
            @RequestParam("name") @NotBlank(message = "文件名不能为空")
            @Pattern(regexp = "^[^/\\\\:*?\"<>|]+$", message = "文件名不能包含非法字符") String name,
            @RequestParam("parentId") Long parentId,
            @RequestParam("file") @NotNull(message = "文件不能为空") MultipartFile file,
            @RequestParam(value = "permissions", required = false) String permissions,
            @RequestParam(value = "mimeTypeName") @NotBlank(message = "MIME类型不能为空") String mimeTypeName) throws IOException {

        try (InputStream inputStream = file.getInputStream()) {
            Node node = nodeService.createFile(name, parentId, inputStream, permissions, mimeTypeName);
            return ResponseEntity.status(201).body(R.success(node, "文件上传成功"));
        }
    }

    /**
     * 下载文件
     * @param id 文件节点ID
     * @return 包含文件流的响应实体
     * @throws IOException IO异常
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> downloadFile(@PathVariable Long id) throws IOException {
        InputStream inputStream = nodeService.getFileById(id);
        if (inputStream == null) {
            // 虽然全局异常处理器会处理，但这里明确抛出更有意义
            throw new NodeNotFoundException("文件不存在或无法读取: id=" + id);
        }

        Node node = nodeService.getNodeById(id);
        if (node == null) {
            // 如果流存在但节点元数据没了（不太可能，但为了健壮性），也应报错
            throw new NodeNotFoundException("文件节点元数据未找到: id=" + id);
        }

        String encodedFileName = URLEncoder.encode(node.getName(), StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        // 使用 "attachment" 并提供 filename* 来处理非ASCII字符
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedFileName + "\"; filename*=UTF-8''" + encodedFileName);
        headers.setContentLength(node.getSize());

        return ResponseEntity.ok()
                .headers(headers)
                .body(new InputStreamResource(inputStream));
    }

    // 删除节点（文件或目录）
    @DeleteMapping("/{id}")
    public ResponseEntity<R<Void>> deleteNode(@PathVariable @NotNull Long id) throws IOException {
        Node node = nodeService.getNodeById(id);
        if (node == null) {
            return ResponseEntity.status(404).body(R.error(404, "Node not found"));
        }

        if (Objects.equals(node.getType(), NodeTypeEnum.DIR.getCode())) {
            nodeService.deleteDirNode(id);
        } else {
            nodeService.deleteFileNode(id);
        }

        return ResponseEntity.ok(R.success(null, "节点删除成功"));
    }
}
