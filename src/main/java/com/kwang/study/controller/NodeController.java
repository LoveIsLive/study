package com.kwang.study.controller;

import com.kwang.study.common.R;
import com.kwang.study.dto.CreateDirectoryDTO;
import com.kwang.study.enums.NodeTypeEnum;
import com.kwang.study.exception.NodeNotFoundException;
import com.kwang.study.pojo.Node;
import com.kwang.study.service.NodeService;
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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/file")
@Validated
public class NodeController {

    private final NodeService nodeService;

    @Autowired
    public NodeController(NodeService nodeService) {
        this.nodeService = nodeService;
    }

    // 创建目录
    @PostMapping("/directories/create")
    public ResponseEntity<R<Node>> createDirectory(@Valid @RequestBody CreateDirectoryDTO request) {
        Node node = nodeService.createDirectory(request.getName(), request.getParentId(), request.getPermissions());
        return ResponseEntity.ok(R.success(node));
    }

    // 列出目录内容
    @GetMapping("/directories/contents")
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

    // 上传文件
    @PostMapping("/files/upload")
    public ResponseEntity<R<Node>> uploadFile(
            @RequestParam("name") @NotBlank String name,
            @RequestParam("parentId") Long parentId,
            @RequestParam("file") @NotNull MultipartFile file,
            @RequestParam(value = "permissions", required = false) String permissions) throws Exception {

        try (InputStream inputStream = file.getInputStream()) {
            Node node = nodeService.createFile(name, parentId, inputStream, permissions);
            return ResponseEntity.ok(R.success(node));
        }
    }

    @GetMapping("/files/{id}/download")
    public ResponseEntity<InputStreamResource> downloadFile(@PathVariable Long id) throws Exception {
        try (InputStream inputStream = nodeService.getFileById(id)) {
            if (inputStream == null) {
                throw new NodeNotFoundException();
            }

            // 获取文件节点信息，用于设置 Content-Disposition
            Node node = nodeService.getNodeById(id);
            String fileName = node.getName();

            // 设置响应头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", fileName);

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(new InputStreamResource(inputStream));
        }
    }

    // 删除节点（文件或目录）
    @DeleteMapping("/nodes/{id}")
    public ResponseEntity<R<Void>> deleteNode(@PathVariable Long id) throws IOException {
        Node node = nodeService.getNodeById(id);
        if (node == null) {
            return ResponseEntity.status(404).body(R.error(404, "Node not found"));
        }

        if (Objects.equals(node.getType(), NodeTypeEnum.DIR.getCode())) {
            nodeService.deleteDirNode(id);
        } else {
            nodeService.deleteFileNode(id);
        }

        return ResponseEntity.ok(R.success(null));
    }
}
