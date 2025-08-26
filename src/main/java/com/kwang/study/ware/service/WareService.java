package com.kwang.study.ware.service;

import com.kwang.study.enums.FileStorageModuleNameEnum;
import com.kwang.study.fs.dto.result.*;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.fs.util.TextMimeUtil;
import com.kwang.study.utils.DownloadUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_PARTIAL_CONTENT;

@Service
public class WareService {
    @Autowired
    private FileStorageService fsService;

    @Transactional
    public VoidResult createDirectory(String path) throws IOException {
        String actualPath = buildActualPath(path);
        return fsService.createDirectory(actualPath);
    }

    /**
     * 创建文件（处理文件去重）
     * 注意：仅仅小文件可以调用这个方法，不会出现OOM
     */
    @Transactional
    public VoidResult createFile(String path, InputStream fileStream, String mimeTypeName) throws IOException {
        String actualPath = buildActualPath(path);
        return fsService.createFile(actualPath, fileStream, mimeTypeName);
    }

    /**
     * 递归删除目录节点（危险操作！！！）
     */
    @Transactional
    public VoidResult deleteDirNode(String path) throws IOException {
        String actualPath = buildActualPath(path);
        return fsService.deleteDirObject(actualPath);
    }


    /**
     * 删除文件节点（带引用计数处理）
     */
    @Transactional
    public VoidResult deleteFileNode(String path) throws IOException {
        String actualPath = buildActualPath(path);
        return fsService.deleteFileObject(actualPath);
    }

    /**
     * 重命名文件节点
     */
    @Transactional
    public VoidResult renameFileNode(String path, String newName) throws IOException {
        String actualPath = buildActualPath(path);
        return fsService.updateFileObject(actualPath, newName, null, null);
    }

    /**
     * 重命名文件节点
     */
    @Transactional
    public VoidResult renameDirNode(String path, String newName) throws IOException {
        String actualPath = buildActualPath(path);
        return fsService.updateDirObject(actualPath, newName);
    }

    /**
     * 列出指定目录的详细内容
     */
    @Transactional
    public DirObjectResult listDirectoryDetailContents(String path) throws IOException {
        String actualPath = buildActualPath(path);
        return fsService.getDirectoryObject(actualPath);
    }

    /**
     * 获取节点的详细信息（包含MIME类型）
     */
    @Transactional
    public GenericObjectResult getNodeDetails(String path) throws IOException {
        String actualPath = buildActualPath(path);
        return fsService.getObjectDesc(actualPath);
    }

    public void downloadFile(String path, String mode, HttpServletRequest request,
                             HttpServletResponse response) throws IOException {
        String actualPath = buildActualPath(path);
        FileObjectResult fileObject = fsService.getFileObject(actualPath);
        DownloadUtils.downloadFile(fileObject, mode, request, response);
    }

    public VoidResult searchNodesBFS(String path, String namePattern, Consumer<SearchNodeResult> resultConsumer) {
        String actualPath = buildActualPath(path);
        return fsService.searchNodesBFS(actualPath, namePattern, resultConsumer);
    }

    /**
     * 初始化分块上传，之后的分块上传需要带上result的uploadId.
     */
    @Transactional
    public InitMultiUploadResult initMultiUpload(String path, String mimeTypeName) throws IOException {
        String actualPath = buildActualPath(path);
        return fsService.initMultiUpload(actualPath, mimeTypeName);
    }

    /**
     * 上传分块，该方法在所有分块上传完毕后，会自动合并。
     */
    @Transactional
    public UploadChunkResult uploadChunkAndAutoMerge(String uploadId, Integer chunkIndex, Integer totalChunks,
                                              InputStream chunkStream) throws IOException {
        return fsService.uploadChunkAndAutoMerge(uploadId, chunkIndex, totalChunks, chunkStream);
    }

    @Transactional
    public MimeTypeResult getAllMimeTypeNames() {
        return fsService.getAllMimeTypeNames();
    }


    // 返回用户该模块的根目录，不以/结尾
    private String buildBasePath() {
        return FileStorageModuleNameEnum.WARE_NAME.getModuleName();
    }

    private String buildActualPath(String path) {
        String basePath = buildBasePath();
        if (!path.startsWith("/"))
            path = "/" + path;
        if (path.endsWith("/"))
            path = path.substring(0, path.length() - 1);
        return basePath + path;
    }
}
