package com.kwang.study.ware.service;

import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.organization.enums.ClassesRoleEnum;
import com.kwang.study.organization.enums.SchoolRoleEnum;
import com.kwang.study.organization.pojo.School;
import com.kwang.study.organization.service.ClassesService;
import com.kwang.study.auth.mapper.UserMapper;
import com.kwang.study.organization.pojo.Classes;
import com.kwang.study.auth.pojo.User;
import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.enums.FileStorageModuleNameEnum;
import com.kwang.study.fs.dto.result.*;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.organization.service.SchoolService;
import com.kwang.study.utils.DownloadUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class WareService {
    @Autowired
    private FileStorageService fsService;
    @Autowired
    private ClassesService classesService;
    @Autowired
    private SchoolService schoolService;
    @Autowired
    private UserInfoUtils userInfoUtils;

    @Transactional
    public VoidResult createDirectory(String path) throws IOException {
        validateWritePermission();

        String actualPath = buildActualPath(path);
        return fsService.createDirectory(actualPath);
    }

    /**
     * 创建文件（处理文件去重）
     * 注意：仅仅小文件可以调用这个方法，不会出现OOM
     */
    @Transactional
    public VoidResult createFile(String path, InputStream fileStream, String mimeTypeName) throws IOException {
        validateWritePermission();

        String actualPath = buildActualPath(path);
        return fsService.createFile(actualPath, fileStream, mimeTypeName);
    }

    /**
     * 递归删除目录节点（危险操作！！！）
     */
    @Transactional
    public VoidResult deleteDirNode(String path) throws IOException {
        validateWritePermission();

        String actualPath = buildActualPath(path);
        return fsService.deleteDirObject(actualPath);
    }


    /**
     * 删除文件节点（带引用计数处理）
     */
    @Transactional
    public VoidResult deleteFileNode(String path) throws IOException {
        validateWritePermission();

        String actualPath = buildActualPath(path);
        return fsService.deleteFileObject(actualPath);
    }

    /**
     * 重命名文件节点
     */
    @Transactional
    public VoidResult renameFileNode(String path, String newName) throws IOException {
        validateWritePermission();

        String actualPath = buildActualPath(path);
        return fsService.updateFileObject(actualPath, newName, null, null);
    }

    /**
     * 重命名文件节点
     */
    @Transactional
    public VoidResult renameDirNode(String path, String newName) throws IOException {
        validateWritePermission();

        String actualPath = buildActualPath(path);
        return fsService.updateDirObject(actualPath, newName);
    }

    /**
     * 列出指定目录的详细内容
     */
    @Transactional
    public DirObjectResult listDirectoryDetailContents(String path) throws IOException {
        String actualPath = buildActualPath(path);
        DirObjectResult result = fsService.getDirectoryObject(actualPath);
        // 管理员，特殊处理规则
        if (AuthenticationUserUtil.currentUserIsAdmin()) {
            // 学校
            if ("/".equals(path)) {
                List<Long> list = result.getFileObjectDescs().stream()
                        .map(desc -> Long.parseLong(desc.getName())).collect(Collectors.toList());
                List<School> schools = schoolService.getBatchSchoolByIds(list);
                for (int i = 0; i < schools.size(); i++) {
                    result.getFileObjectDescs().get(i).setName(schools.get(i).getName());
                }
            } else if (path.lastIndexOf('/') == 0) {
                // 班级
                List<Long> list = result.getFileObjectDescs().stream()
                        .map(desc -> Long.parseLong(desc.getName())).collect(Collectors.toList());
                List<Classes> classesList = classesService.getBatchClassByIds(list);
                for (int i = 0; i < classesList.size(); i++) {
                    result.getFileObjectDescs().get(i).setName(classesList.get(i).getName());
                }
            }
        }
        if (userInfoUtils.currentUserInSchoolIsPrincipal() && "/".equals(path)) {
            // 班级
            List<Long> list = result.getFileObjectDescs().stream()
                    .map(desc -> Long.parseLong(desc.getName())).collect(Collectors.toList());
            List<Classes> classesList = classesService.getBatchClassByIds(list);
            for (int i = 0; i < classesList.size(); i++) {
                result.getFileObjectDescs().get(i).setName(classesList.get(i).getName());
            }
        }
        return result;
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
        validateWritePermission();

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
        User user = userInfoUtils.getCurrentUserInfoWithOrgInfo();
        Assert.isTrue(user != null, "没有查找到该用户");
        if (AuthenticationUserUtil.currentUserIsAdmin()) {
            // 管理员能看见all
            return basePath.toString();
        }
        if (user.getSchoolMember() != null && SchoolRoleEnum.PRINCIPAL.getRole()
                .equals(user.getSchoolMember().getRole())) {
            // 校长
            basePath.append('/').append(user.getSchoolMember().getSchool().getId());
        } else if (user.getClassMember() != null) {
            // 教师/学生
            basePath.append('/')
                    .append(user.getClassMember().getClasses().getSchoolId())
                    .append('/')
                    .append(user.getClassMember().getClasses().getId());
        } else {
            throw new IllegalArgumentException("用户既无班级信息也无学校信息");
        }

        return basePath.toString();
    }

    private String buildActualPath(String path) {
        // 管理员，学校/班级特殊处理规则
        if (AuthenticationUserUtil.currentUserIsAdmin() && !"/".equals(path)) {
            String[] parts = path.split("/");
            String schoolName = parts[1];
            School school = schoolService.getSchoolByName(schoolName);
            path = path.replaceFirst(schoolName, school.getId().toString());

            if (parts.length > 2) {
                String className = parts[2];
                Classes classes = classesService.getClassByName(className, school.getId());
                Assert.isTrue(classes != null, "路径无班级名称");
                path = path.replaceFirst(className, classes.getId().toString());
            }
        }
        // 校长，班级特殊处理规则
        if (!"/".equals(path)) {
            User user = userInfoUtils.getCurrentUserInfoWithOrgInfo();
            // 是校长
            if (user != null && user.getSchoolMember() != null &&
                    SchoolRoleEnum.PRINCIPAL.getRole().equals(user.getSchoolMember().getRole())) {
                String[] parts = path.split("/");
                String className = parts[1];
                Classes classes = classesService.getClassByName(className, user.getSchoolMember().getSchoolId());
                Assert.isTrue(classes != null, "路径无班级名称");
                path = path.replaceFirst(className, classes.getId().toString());
            }
        }

        String basePath = buildBasePath();
        if (!path.startsWith("/"))
            path = "/" + path;
        if (path.endsWith("/"))
            path = path.substring(0, path.length() - 1);

        return basePath + path;
    }

    private void validateWritePermission() {
        User user = userInfoUtils.getCurrentUserInfoWithOrgInfo();
        Assert.notNull(user, "无写权限");

        boolean have = AuthenticationUserUtil.currentUserIsAdmin() ||
                (user.getSchoolMember() != null && SchoolRoleEnum.PRINCIPAL.getRole().equals(user.getSchoolMember().getRole())) ||
                (user.getClassMember() != null && ClassesRoleEnum.TEACHER.getRole().equals(user.getClassMember().getRole()))
                ;
        Assert.isTrue(have, "无写权限");
    }
}
