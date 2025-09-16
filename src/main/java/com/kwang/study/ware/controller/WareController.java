package com.kwang.study.ware.controller;

import com.kwang.study.common.R;
import com.kwang.study.fs.dto.result.*;
import com.kwang.study.utils.PathUtils;
import com.kwang.study.ware.dto.request.*;
import com.kwang.study.ware.service.WareService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.kwang.study.constant.ApiPrefixConstant.WARE_BASE_PREFIX;
import static com.kwang.study.constant.RedisKeyPrefixConstant.DOWNLOAD_ID_PREFIX;

@RestController
@RequestMapping(WARE_BASE_PREFIX + "/home")
@Validated
@Slf4j
public class WareController {
    @Autowired
    private WareService wareService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    /**
     * 创建目录
     */
    @PostMapping("/create/directories")
    @PreAuthorize("hasRole('ROLE_TEACHER')")
    public ResponseEntity<R<VoidResult>> createDirectory(@RequestParam("path") String path) throws IOException {
        Assert.isTrue(PathUtils.isOrdinaryPath(path), "路径非法: " + path);

        VoidResult voidResult = wareService.createDirectory(path);
        return build(voidResult);
    }

    /**
     * 上传小文件
     */
    @PostMapping("/create/files")
    @PreAuthorize("hasRole('ROLE_TEACHER')")
    public ResponseEntity<R<VoidResult>> uploadFile(@Valid UploadFileRequestDTO requestDTO,
                                                    @RequestParam("file") MultipartFile file) throws IOException {
        requestDTO.check();

        try (InputStream input = file.getInputStream()) {
            VoidResult voidResult = wareService.createFile(requestDTO.getPath(),
                    input, requestDTO.getMimeTypeName());
            return build(voidResult);
        }
    }

    // 删除目录节点
    @DeleteMapping("/delete/dir")
    @PreAuthorize("hasRole('ROLE_TEACHER')")
    public ResponseEntity<R<VoidResult>> deleteDireNode(@RequestParam("path") String path) throws IOException {
        Assert.isTrue(PathUtils.isOrdinaryPath(path), "路径非法: " + path);

        VoidResult voidResult = wareService.deleteDirNode(path);
        return build(voidResult);
    }

    // 删除文件节点
    @DeleteMapping("/delete/file")
    @PreAuthorize("hasRole('ROLE_TEACHER')")
    public ResponseEntity<R<VoidResult>> deleteFileNode(@RequestParam("path") String path) throws IOException {
        Assert.isTrue(PathUtils.isOrdinaryPath(path), "路径非法: " + path);

        VoidResult voidResult = wareService.deleteFileNode(path);
        return build(voidResult);
    }

    // 更新目录节点
    @PostMapping("/update/dir")
    @PreAuthorize("hasRole('ROLE_TEACHER')")
    public ResponseEntity<R<VoidResult>> updateDireNode(@RequestParam("path") String path,
                                                        @RequestParam("newName") String newName) throws IOException {
        Assert.isTrue(PathUtils.isOrdinaryPath(path), "路径非法: " + path);

        VoidResult voidResult = wareService.renameDirNode(path, newName);
        return build(voidResult);
    }

    // 更新文件节点
    @PostMapping("/update/file")
    @PreAuthorize("hasRole('ROLE_TEACHER')")
    public ResponseEntity<R<VoidResult>> updateFileNode(@Valid @RequestBody UpdateFileRequestDTO requestDTO) throws IOException {
        requestDTO.check();

        VoidResult voidResult = wareService.renameFileNode(requestDTO.getPath(), requestDTO.getNewName());
        return build(voidResult);
    }

    /**
     * 列出目录详细内容（包括mimeTypeName）
     */
    @GetMapping("/get/dir")
    public ResponseEntity<R<DirObjectResult>> listDirectoryDetailContents(
            @RequestParam("path") String path) throws IOException {
        Assert.isTrue(PathUtils.isValidPath(path), "路径非法: " + path);

        DirObjectResult dirObjectResult = wareService.listDirectoryDetailContents(path);
        return build(dirObjectResult);
    }

    /**
     * 获取单个节点的详细属性
     */
    @GetMapping("/get/node")
    public ResponseEntity<R<GenericObjectResult>> getNodeDetails(@RequestParam("path") String path) throws IOException {
        Assert.isTrue(PathUtils.isOrdinaryPath(path), "路径非法: " + path);

        GenericObjectResult result = wareService.getNodeDetails(path);
        return build(result);
    }

    @GetMapping("/download")
    public void downloadFile(@RequestParam("path") String path,
                             @RequestParam(name = "mode", defaultValue = "attachment") String mode,
                             @RequestParam("token") String token,
                             HttpServletRequest request, HttpServletResponse response) throws IOException {
        Assert.isTrue(PathUtils.isOrdinaryPath(path), "路径非法:" + path);

        String tokenPath = redisTemplate.opsForValue().get(DOWNLOAD_ID_PREFIX + token);
        if (!Objects.equals(path, tokenPath)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().print("没有权限");
            response.setContentType("text/plain; charset=UTF-8");
            return;
        }

        wareService.downloadFile(path, mode, request, response);
    }

    @PostMapping("/chunk/init")
    @PreAuthorize("hasRole('ROLE_TEACHER')")
    public ResponseEntity<R<InitMultiUploadResult>> initMultiUpload(@Valid @RequestBody InitUploadBigFileRequestDTO requestDTO) throws IOException {
        requestDTO.check();

        InitMultiUploadResult uploadResult = wareService.initMultiUpload(requestDTO.getPath(), requestDTO.getMimeTypeName());
        return build(uploadResult);
    }

    @PostMapping("/chunk/upload")
    public ResponseEntity<?> uploadChunk(@Valid UploadChunkRequestDTO requestDTO,
                                         @RequestParam("chunk") MultipartFile chunk) throws Exception {
        requestDTO.check();

        try (InputStream input = chunk.getInputStream()) {
            GenericObjectResult chunkResult = wareService.uploadChunk(requestDTO.getUploadId(), requestDTO.getChunkIndex(),
                    requestDTO.getTotalChunks(), input);

            if (Boolean.TRUE.equals(chunkResult.getSuccess())){
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(R.success("上传成功"));
            } else {
                return ResponseEntity
                        .status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(R.error(chunkResult.getErrorMessage()));
            }
        }
    }

    @PostMapping("/chunk/merge")
    public ResponseEntity<?> mergeChunk(@Valid MergeChunkRequestDTO requestDTO) throws Exception {
        requestDTO.check();

        GenericObjectResult chunkResult = wareService.mergeChunk(requestDTO.getUploadId(), requestDTO.getTotalChunks());

        if (Boolean.TRUE.equals(chunkResult.getSuccess())){
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(R.success("合并成功"));
        } else {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(R.error(chunkResult.getErrorMessage()));
        }
    }

    @GetMapping("/get/mime")
    public ResponseEntity<R<MimeTypeResult>> getAllMimeTypeNames() throws IOException {
        MimeTypeResult result = wareService.getAllMimeTypeNames();
        return build(result);
    }

    @GetMapping("/get/downloadId")
    public ResponseEntity<R<String>> produceDownloadUUID(@RequestParam("path") String path) {
        Assert.isTrue(PathUtils.isOrdinaryPath(path), "路径非法:" + path);
        String downloadId = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(DOWNLOAD_ID_PREFIX + downloadId, path, 30, TimeUnit.MINUTES);
        return ResponseEntity.ok(R.success(downloadId));
    }


    private <T extends BaseResult> ResponseEntity<R<T>> build(T baseResult) {
        if (baseResult == null || !Boolean.TRUE.equals(baseResult.getSuccess())) {
            return ResponseEntity.status(500).body(R.error(baseResult == null ?
                    "无操作结果" : baseResult.getErrorMessage()));
        } else {
            return ResponseEntity.ok(R.success(baseResult));
        }
    }
}
