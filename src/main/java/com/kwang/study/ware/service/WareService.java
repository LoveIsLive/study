package com.kwang.study.ware.service;

import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.fs.mapper.NodeMapper;
import com.kwang.study.fs.pojo.Node;
import com.kwang.study.llm.service.LLMService;
import com.kwang.study.organization.enums.ClassesRoleEnum;
import com.kwang.study.organization.enums.SchoolRoleEnum;
import com.kwang.study.organization.pojo.ClassMember;
import com.kwang.study.organization.pojo.School;
import com.kwang.study.organization.pojo.SchoolMember;
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
import com.kwang.study.ware.mapper.NodeMetadataMapper;
import com.kwang.study.ware.pojo.NodeMetadata;
import com.kwang.study.ware.service.async.AsyncWareTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestParam;

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

    @Autowired
    private NodeMetadataMapper nodeMetadataMapper;
    @Autowired
    private NodeMapper nodeMapper;
    @Autowired
    private AsyncWareTaskService asyncWareTaskService;

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

        // 过滤
        if (userInfoUtils.currentUserInClassIsStudent() || userInfoUtils.currentUserInClassIsGuest()) {
            List<DirObjectResult.FileObjectDesc> filtered = result.getFileObjectDescs().stream()
                    .filter(desc -> desc.getIsHidden() == null || desc.getIsHidden() == 0)
                    .collect(Collectors.toList());
            result.setFileObjectDescs(filtered);
        }
        return result;
    }

    /**
     * 获取节点的详细信息（包含MIME类型）
     */
    @Transactional
    public GenericObjectResult getNodeDetails(String path) throws IOException {
        String actualPath = buildActualPath(path);

        checkNodeVisibilityForStudent(actualPath);

        return fsService.getObjectDesc(actualPath);
    }

    public void downloadFile(String path, String mode, HttpServletRequest request,
                             HttpServletResponse response) throws IOException {
        String actualPath = buildActualPath(path);

        checkNodeVisibilityForStudent(actualPath);

        FileObjectResult fileObject = fsService.getFileObject(actualPath);
        DownloadUtils.downloadFile(fileObject, mode, request, response);
    }

    public VoidResult searchNodesBFS(String path, String namePattern, Consumer<SearchNodeResult> resultConsumer) {
        String actualPath = buildActualPath(path);

        checkNodeVisibilityForStudent(actualPath);

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

    @Transactional
    public NodeMetadata getFileAISummary(String path) {
        String actualPath = buildActualPath(path);

        // 这里有点越权了，应该由fsService暴露一个系统内部使用的 获取node_id的方法。
        Node node = nodeMapper.selectNodeByPath(actualPath);
        return nodeMetadataMapper.selectByNodeId(node.getId());
    }

    @Transactional
    public VoidResult updateFileAISummary(String path, String summary) {
        String actualPath = buildActualPath(path);

        // 这里有点越权了，应该由fsService暴露一个系统内部使用的 获取node_id的方法。
        Node node = nodeMapper.selectNodeByPath(actualPath);
        nodeMetadataMapper.updateOrCreateByNodeId(NodeMetadata.builder()
                .nodeId(node.getId())
                .aiSummary(summary)
                .build());
        return VoidResult.success();
    }

    /**
     * 教师设置文件/目录的隐藏状态
     */
    @Transactional
    public VoidResult setNodeHidden(String path, Integer isHidden) throws IOException {
        // 只有教师或以上的权限可以修改隐藏状态
        validateWritePermission();

        String actualPath = buildActualPath(path);
        return fsService.updateNodeHiddenStatus(actualPath, isHidden);
    }

    /**
     * 提交打包任务
     */
    public void submitArchiveTask(String sourceDirPath, String zipFileName) {
        // 1. 主线程鉴权
        validateWritePermission();
        // 2. 主线程获取当前用户名
        String username = AuthenticationUserUtil.getCurrentUserName();
        // 3. 计算底层真实路径
        String actualSourcePath = buildActualPath(sourceDirPath);
        String parentPath = actualSourcePath.substring(0, actualSourcePath.lastIndexOf('/'));
        String actualZipPath = parentPath + "/" + zipFileName;
        // 4. 提交异步任务
        asyncWareTaskService.executeArchiveTask(actualSourcePath, actualZipPath, username, sourceDirPath, zipFileName);
    }

    /**
     * 提交解压任务
     */
    public void submitUnarchiveTask(String zipFilePath, String targetDirPath) {
        // 1. 主线程鉴权
        validateWritePermission();
        // 2. 主线程获取当前用户名
        String username = AuthenticationUserUtil.getCurrentUserName();
        // 3. 计算底层真实路径
        String actualZipPath = buildActualPath(zipFilePath);
        String actualTargetDirPath = buildActualPath(targetDirPath);
        // 4. 提交异步任务
        asyncWareTaskService.executeUnarchiveTask(actualZipPath, actualTargetDirPath, username, zipFilePath, targetDirPath);
    }


    // 返回用户该模块的根目录，不以/结尾
    private String buildBasePath() {
        StringBuilder basePath = new StringBuilder(FileStorageModuleNameEnum.WARE_NAME.getModuleName());

        if (AuthenticationUserUtil.currentUserIsAdmin()) {
            return basePath.toString();
        }

        // 修正：调用感知 Header 的新方法
        SchoolMember activeSM = userInfoUtils.getCurrentActiveSchoolMember();
        ClassMember activeCM = userInfoUtils.getCurrentActiveClassMember();

        if (activeSM != null && SchoolRoleEnum.PRINCIPAL.getRole().equals(activeSM.getRole())) {
            // 校长身份：进入学校根目录
            basePath.append('/').append(activeSM.getSchoolId());
        } else if (activeCM != null) {
            // 教师/学生身份：进入 班级目录 /ware/{schoolId}/{classId}
            basePath.append('/')
                    .append(activeCM.getClasses().getSchoolId())
                    .append('/')
                    .append(activeCM.getClassId());
        } else {
            throw new IllegalArgumentException("用户当前身份无法访问存储空间");
        }

        return basePath.toString();
    }

    public String buildActualPath(String path) {
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
        SchoolMember activeSM = userInfoUtils.getCurrentActiveSchoolMember();
        // 校长逻辑修正：使用 activeSM 而不是 user.getSchoolMember()
        if (!"/".equals(path) && activeSM != null &&
                SchoolRoleEnum.PRINCIPAL.getRole().equals(activeSM.getRole())) {
            String[] parts = path.split("/");
            String className = parts[1];
            // 确保在该校长的当前学校下查找班级
            Classes classes = classesService.getClassByName(className, activeSM.getSchoolId());
            Assert.isTrue(classes != null, "路径无效或无权访问该班级");
            path = path.replaceFirst(className, classes.getId().toString());
        }

        String basePath = buildBasePath();
        if (!path.startsWith("/"))
            path = "/" + path;
        if (path.endsWith("/"))
            path = path.substring(0, path.length() - 1);

        String actualPath = basePath + path;
        // === 新增：文件系统底层安全拦截 - 防访客越权 ===
        // 师生/访客的 actualPath 结构通常为：/ware/{schoolId}/{classId}/{courseId}/...
        if (userInfoUtils.currentUserInClassIsGuest()) {
            String[] parts = actualPath.split("/");
            // parts[0]="", parts[1]="ware", parts[2]=schoolId, parts[3]=classId, parts[4]=courseId
            if (parts.length > 4) {
                try {
                    Long classId = Long.parseLong(parts[3]);
                    Long courseId = Long.parseLong(parts[4]);
                    if (!userInfoUtils.canAccessCourse(courseId, classId)) {
                        throw new IllegalArgumentException("越权拦截：访客无权访问该课程的资料");
                    }
                } catch (NumberFormatException ignored) {
                    // 如果解析不出 ID，说明可能在访问根目录等非课程节点，放行
                }
            }
        }

        return actualPath;
    }

    /**
     * 将内部存储路径转换为用户可见的显示路径
     * 内部路径示例: /ware/1/2/文件夹/文件.txt
     * 教师显示: /文件夹/文件.txt
     * 校长显示: /测试班级/文件夹/文件.txt
     * 管理员显示: /测试学校/测试班级/文件夹/文件.txt
     */
    public String getDisplayPath(String fullInternalPath) {
        // 1. 去掉模块前缀 /ware
        String prefix = FileStorageModuleNameEnum.WARE_NAME.getModuleName();
        if (!fullInternalPath.startsWith(prefix)) return fullInternalPath;
        String pathWithoutModule = fullInternalPath.substring(prefix.length()); // 得到 /1/2/文件夹/文件.txt

        String[] parts = pathWithoutModule.split("/");
        // parts[0] 为空字符串，因为路径以 / 开头
        // parts[1] 为 schoolId
        // parts[2] 为 classId (如果存在)

        if (AuthenticationUserUtil.currentUserIsAdmin()) {
            // 管理员逻辑：/学校名/班级名/...
            if (parts.length > 1) {
                School school = schoolService.getSchoolById(Long.parseLong(parts[1]));
                pathWithoutModule = pathWithoutModule.replaceFirst("/" + parts[1], "/" + school.getName());
            }
            if (parts.length > 2) {
                Classes cls = classesService.getClassById(Long.parseLong(parts[2]));
                pathWithoutModule = pathWithoutModule.replaceFirst("/" + parts[2], "/" + cls.getName());
            }
            return pathWithoutModule;
        }

        if (userInfoUtils.currentUserInSchoolIsPrincipal()) {
            // 校长逻辑：去掉学校ID，将班级ID换成班级名 -> /班级名/...
            // pathWithoutModule 此时是 /1/2/...
            if (parts.length > 2) {
                Classes cls = classesService.getClassById(Long.parseLong(parts[2]));
                // 先去掉 /schoolId
                String strippedSchool = pathWithoutModule.replaceFirst("/" + parts[1], "");
                // 再把 /classId 换成 /className
                return strippedSchool.replaceFirst("/" + parts[2], "/" + cls.getName());
            }
            return "/";
        }

        ClassMember activeCM = userInfoUtils.getCurrentActiveClassMember();
        if (activeCM != null) {
            // 师生逻辑：直接去掉 /schoolId/classId -> /...
            if (parts.length > 2) {
                return pathWithoutModule.replaceFirst("/" + parts[1] + "/" + parts[2], "");
            }
        }

        return "/";
    }

    private void validateWritePermission() {
        SchoolMember acSM = userInfoUtils.getCurrentActiveSchoolMember();
        ClassMember acCM = userInfoUtils.getCurrentActiveClassMember();

        boolean have = AuthenticationUserUtil.currentUserIsAdmin() ||
                (acSM != null && SchoolRoleEnum.PRINCIPAL.getRole().equals(acSM.getRole())) ||
                (acCM != null && ClassesRoleEnum.TEACHER.getRole().equals(acCM.getRole()))
                ;
        Assert.isTrue(have, "无写权限");
    }

    /**
     * 校验学生对目标节点的访问权限（向上追溯父节点隐藏状态）
     */
    private void checkNodeVisibilityForStudent(String actualPath) {
        // 如果不是学生/访客，直接放行
        if (!userInfoUtils.currentUserInClassIsStudent() && !userInfoUtils.currentUserInClassIsGuest()) {
            return;
        }

        // 1. 获取目标节点
        Node targetNode = nodeMapper.selectNodeByPath(actualPath);
        if (targetNode == null) {
            throw new IllegalArgumentException("文件或目录不存在");
        }

        // 2. 利用 CTE 高效查询是否被隐藏
        boolean isHidden = nodeMapper.isNodeOrAncestorHidden(targetNode.getId());
        if (isHidden) {
            throw new IllegalArgumentException("当前资源或其上级目录已被教师隐藏，您无权访问");
        }
    }
}
