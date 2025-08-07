package com.kwang.study.controller.fs;

import com.kwang.study.common.R;
import com.kwang.study.dto.fs.request.CreateDirectoryDTO;
import com.kwang.study.dto.fs.request.UploadFileRequestDTO;
import com.kwang.study.dto.fs.result.NodeDetailDTO;
import com.kwang.study.dto.fs.result.CDDirResult;
import com.kwang.study.enums.NodeTypeEnum;
import com.kwang.study.pojo.fs.Node;
import com.kwang.study.service.fs.NodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import static com.kwang.study.constant.ApiPrefixConstant.FS_BASE_PREFIX;

@RestController
@RequestMapping(FS_BASE_PREFIX + "/nodes")
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
    @PreAuthorize("hasRole('ROLE_TEACHER')")
    public ResponseEntity<R<Node>> createDirectory(@Valid @RequestBody CreateDirectoryDTO request) {
        request.check();

        Node node = nodeService.createDirectory(request.getName(), request.getParentId(), request.getPermissions());
        return ResponseEntity.status(201).body(R.success(node, "目录创建成功"));
    }

    /**
     * 列出目录详细内容（包括mimeTypeName）
     * @param parentId 父目录ID。如果为null，则列出根目录内容。
     * @return 目录下的节点列表
     */
    @GetMapping
    public ResponseEntity<R<List<NodeDetailDTO>>> listDirectoryDetailContents(
            @RequestParam(required = false) Long parentId) {
        List<NodeDetailDTO> nodes = nodeService.listDirectoryDetailContents(parentId);
        return ResponseEntity.ok(R.success(nodes));
    }

    /**
     * 上传小文件
     * @param requestDTO 请求参数
     * @return 包含新创建的文件节点的响应
     * @throws IOException IO异常
     */
    @PostMapping("/files")
    @PreAuthorize("hasRole('ROLE_TEACHER')")
    public ResponseEntity<R<Node>> uploadFile(@Valid @RequestBody UploadFileRequestDTO requestDTO) throws IOException {
        requestDTO.check();

        Node node = nodeService.createFile(requestDTO.getName(), requestDTO.getParentId(),
                requestDTO.getFile().getInputStream(), requestDTO.getPermissions(), requestDTO.getMimeTypeName());
        return ResponseEntity.status(201).body(R.success(node, "文件上传成功"));
    }

    @GetMapping("/{id}/download")
    public void downloadFile(@PathVariable @NotNull Long id,
                             @RequestParam(name = "mode", defaultValue = "attachment") String mode,
                             HttpServletRequest request, HttpServletResponse response) throws IOException {
        nodeService.downloadFile(id, mode, request, response);
    }

    // 删除节点（文件或目录）
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_TEACHER')")
    public ResponseEntity<R<Void>> deleteNode(@PathVariable Long id) throws IOException {
        Node node = nodeService.getNodeById(id);
        if (node == null) {
            return ResponseEntity.status(404).body(R.error(404, "Node not found"));
        }

        if (Objects.equals(node.getType(), NodeTypeEnum.DIR.getCode())) {
            nodeService.deleteDirNode(id);
        } else {
            // 不区分 文件和分块上传中间态
            nodeService.deleteFileNode(id);
        }

        return ResponseEntity.ok(R.success(null, "节点删除成功"));
    }

    /**
     * 重命名一个节点（文件或目录）
     * @param id 节点ID
     * @param newName 新名称
     * @return 更新后的节点信息
     */
    @PatchMapping("/{id}/rename")
    @PreAuthorize("hasRole('ROLE_TEACHER')")
    public ResponseEntity<R<Node>> renameNode(
            @PathVariable Long id,
            @RequestParam(value = "newName") @NotBlank(message = "新名称不能为空")
            @Pattern(regexp = "^[^/\\\\:*?\"<>|]+$", message = "名称不能包含非法字符") String newName) {
        Node updatedNode = nodeService.renameNode(id, newName);
        return ResponseEntity.ok(R.success(updatedNode, "重命名成功"));
    }

    /**
     * 获取单个节点的详细属性
     * @param id 节点ID
     * @return 节点的详细信息
     */
    @GetMapping("/{id}/details")
    public ResponseEntity<R<NodeDetailDTO>> getNodeDetails(@PathVariable Long id) {
        NodeDetailDTO nodeDetails = nodeService.getNodeDetails(id);
        return ResponseEntity.ok(R.success(nodeDetails));
    }

    /**
     * 根据路径列出目录内容 (增强版cd命令)
     * @param path 目标路径，可以是绝对路径（以'/'开头）或相对路径。
     * @param currentDirectoryId 当前目录ID，当路径为相对路径时需要提供。
     * @return 目标目录的id,fullPath,和子节点详细信息
     */
    @GetMapping("/list-by-path")
    public ResponseEntity<R<CDDirResult>> listDirectoryByPath(
            @RequestParam("path") String path,
            @RequestParam(required = false) Long currentDirectoryId) {

        CDDirResult cdDirResult = nodeService.listDirectoryByPath(path, currentDirectoryId);
        return ResponseEntity.ok(R.success(cdDirResult));
    }
}
