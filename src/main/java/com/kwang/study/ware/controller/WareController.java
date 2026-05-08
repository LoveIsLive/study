package com.kwang.study.ware.controller;

import com.kwang.study.auth.custom.CustomUserDetails;
import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.common.R;
import com.kwang.study.fs.dto.result.*;
import com.kwang.study.organization.pojo.ClassMember;
import com.kwang.study.organization.pojo.SchoolMember;
import com.kwang.study.utils.PathUtils;
import com.kwang.study.ware.dto.cache.DownloadTokenDTO;
import com.kwang.study.ware.dto.request.*;
import com.kwang.study.ware.mapper.NodeMetadataMapper;
import com.kwang.study.ware.pojo.NodeMetadata;
import com.kwang.study.ware.service.WareService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
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
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private UserInfoUtils userInfoUtils;

    @Autowired
    private UserDetailsService userDetailsService;

    /**
     * 创建目录
     */
    @PostMapping("/create/directories")
    public ResponseEntity<R<VoidResult>> createDirectory(@RequestParam("path") String path) throws IOException {
        Assert.isTrue(PathUtils.isOrdinaryPath(path), "路径非法: " + path);

        VoidResult voidResult = wareService.createDirectory(path);
        return build(voidResult);
    }

    /**
     * 上传小文件
     */
    @PostMapping("/create/files")
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
    public ResponseEntity<R<VoidResult>> deleteDireNode(@RequestParam("path") String path) throws IOException {
        Assert.isTrue(PathUtils.isOrdinaryPath(path), "路径非法: " + path);

        VoidResult voidResult = wareService.deleteDirNode(path);
        return build(voidResult);
    }

    // 删除文件节点
    @DeleteMapping("/delete/file")
    public ResponseEntity<R<VoidResult>> deleteFileNode(@RequestParam("path") String path) throws IOException {
        Assert.isTrue(PathUtils.isOrdinaryPath(path), "路径非法: " + path);

        VoidResult voidResult = wareService.deleteFileNode(path);
        return build(voidResult);
    }

    // 更新目录节点
    @PostMapping("/update/dir")
    public ResponseEntity<R<VoidResult>> updateDireNode(@RequestParam("path") String path,
                                                        @RequestParam("newName") String newName) throws IOException {
        Assert.isTrue(PathUtils.isOrdinaryPath(path), "路径非法: " + path);

        VoidResult voidResult = wareService.renameDirNode(path, newName);
        return build(voidResult);
    }

    // 更新文件节点
    @PostMapping("/update/file")
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

        DownloadTokenDTO downloadTokenDTO = (DownloadTokenDTO) redisTemplate.opsForValue().get(DOWNLOAD_ID_PREFIX + token);
        if (downloadTokenDTO == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().print("下载失败");
            response.setContentType("text/plain; charset=UTF-8");
            return;
        }
        if (!Objects.equals(path, downloadTokenDTO.getPath())) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().print("没有权限");
            response.setContentType("text/plain; charset=UTF-8");
            return;
        }

        CustomUserDetails userDetails = (CustomUserDetails) userDetailsService.loadUserByUsername(downloadTokenDTO.getUsername());
        userDetails.setAuthorities(downloadTokenDTO.getAuthorities());

        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                userDetails, null, downloadTokenDTO.getAuthorities());

        SecurityContextHolder.setContext(AuthenticationUserUtil
                .newSecurityContext(authToken));

        try {
            userInfoUtils.setManualContext(downloadTokenDTO.getActiveCM(), downloadTokenDTO.getActiveSM());
            wareService.downloadFile(path, mode, request, response);
        } finally {
            userInfoUtils.clearManualContext();
            SecurityContextHolder.clearContext();
        }
    }

    @PostMapping("/chunk/init")
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
        redisTemplate.opsForValue().set(DOWNLOAD_ID_PREFIX + downloadId, new DownloadTokenDTO(path,
                        AuthenticationUserUtil.getCurrentUserName(),
                        Optional.ofNullable(userInfoUtils.getCurrentActiveSchoolMember())
                                .map(SchoolMember::getSchoolId)
                                .orElse(null),
                        Optional.ofNullable(userInfoUtils.getCurrentActiveClassMember())
                                .map(ClassMember::getClassId)
                                .orElse(null),
                        AuthenticationUserUtil.getCurrentUserAuthorities()
                        ),
                30, TimeUnit.MINUTES);
        return ResponseEntity.ok(R.success(downloadId));
    }

    @PostMapping("/update/summary")
    public ResponseEntity<R<VoidResult>> updateFileAISummary(@RequestParam("path") String path,
                                                           @RequestParam("summary") String summary) throws IOException {
        Assert.isTrue(PathUtils.isOrdinaryPath(path), "路径非法: " + path);

        VoidResult voidResult = wareService.updateFileAISummary(path, summary);
        return build(voidResult);
    }

    /**
     * 设置文件/目录的隐藏状态 (仅教师及以上权限)
     */
    @PostMapping("/update/hidden")
    public ResponseEntity<R<VoidResult>> updateNodeHidden(
            @RequestParam("path") String path,
            @RequestParam("isHidden") Integer isHidden) throws IOException {

        Assert.isTrue(PathUtils.isOrdinaryPath(path), "路径非法: " + path);
        Assert.isTrue(isHidden == 0 || isHidden == 1, "非法的隐藏状态值");

        VoidResult voidResult = wareService.setNodeHidden(path, isHidden);
        return build(voidResult);
    }

    /**
     * 将目录归档为 ZIP 压缩包 (教师及以上权限)
     */
    @PostMapping("/archive")
    public ResponseEntity<R<VoidResult>> archiveDirectory(@Valid @RequestBody ArchiveRequestDTO requestDTO) throws IOException {
        requestDTO.check();
        VoidResult result = wareService.archiveDirectory(requestDTO.getSourceDirPath(), requestDTO.getZipFileName());
        return build(result);
    }

    /**
     * 将 ZIP 压缩包解压到指定目录 (教师及以上权限)
     */
    @PostMapping("/unarchive")
    public ResponseEntity<R<VoidResult>> unarchiveFile(@Valid @RequestBody UnarchiveRequestDTO requestDTO) throws IOException {
        requestDTO.check();
        VoidResult result = wareService.unarchiveFile(requestDTO.getZipFilePath(), requestDTO.getTargetDirPath());
        return build(result);
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
