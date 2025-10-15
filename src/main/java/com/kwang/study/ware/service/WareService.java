package com.kwang.study.ware.service;

import com.kwang.study.auth.mapper.UserMapper;
import com.kwang.study.auth.pojo.Role;
import com.kwang.study.auth.pojo.User;
import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.enums.FileStorageModuleNameEnum;
import com.kwang.study.fs.dto.result.*;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.fs.util.TextMimeUtil;
import com.kwang.study.utils.DownloadUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_PARTIAL_CONTENT;

@Service
public class WareService {
    @Autowired
    private FileStorageService fsService;
    @Autowired
    private UserMapper userMapper;

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
     * 上传分块。
     */
    @Transactional
    public GenericObjectResult uploadChunk(String uploadId, Integer chunkIndex, Integer totalChunks,
                                              InputStream chunkStream) throws IOException {
        return fsService.uploadChunk(uploadId, chunkIndex, totalChunks, chunkStream);
    }

    @Transactional
    public GenericObjectResult mergeChunk(String uploadId, Integer totalChunks) throws IOException {
        return fsService.mergeChunk(uploadId, totalChunks);
    }

    @Transactional
    public MimeTypeResult getAllMimeTypeNames() {
        return fsService.getAllMimeTypeNames();
    }


    // 返回用户该模块的根目录，不以/结尾
    private String buildBasePath() {
        StringBuilder basePath = new StringBuilder(FileStorageModuleNameEnum.WARE_NAME.getModuleName());
        String userName = AuthenticationUserUtil.getCurrentUserName();
        User user = userMapper.findByUsernameWithClasses(userName);
        Assert.isTrue(user != null, "没有查找到该用户");
        List<Role> roles = user.getRoles();
        if (!CollectionUtils.isEmpty(roles)) {
            for (Role role : roles) {
                if ("ROLE_ADMIN".equals(role.getName())) {
                    // 管理员能看见all
                    return basePath.toString();
                }
            }
        }
        // 其余只能看见本班级的内容
        Assert.isTrue(user.getClassMember() != null &&
                user.getClassMember().getClasses() != null &&
                user.getClassMember().getClasses().getName() != null, "用户无班级信息");

        String classesName = user.getClassMember().getClasses().getName();
        basePath.append('/').append(classesName);
        return basePath.toString();
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
