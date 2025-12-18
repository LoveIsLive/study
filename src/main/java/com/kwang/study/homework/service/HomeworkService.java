package com.kwang.study.homework.service;

import com.kwang.study.auth.mapper.UserMapper;
import com.kwang.study.auth.pojo.User;
import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.fs.dto.result.MimeTypeIdResult;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.homework.dto.request.*;
import com.kwang.study.homework.enums.HomeworkSubmissionStatusEnum;
import com.kwang.study.homework.pojo.*;
import com.kwang.study.homework.mapper.AttachmentMapper;
import com.kwang.study.homework.mapper.HomeworkMapper;
import com.kwang.study.homework.mapper.HomeworkSubmissionMapper;
import com.kwang.study.homework.service.async.AsyncCleanupFileObjService;
import com.kwang.study.organization.enums.ClassesRoleEnum;
import com.kwang.study.organization.enums.SchoolRoleEnum;
import com.kwang.study.organization.mapper.ClassMemberMapper;
import com.kwang.study.organization.service.ClassMemberService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;
import cn.hutool.core.lang.UUID;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.kwang.study.constant.RedisKeyPrefixConstant.UPLOAD_ID_PREFIX;
import static com.kwang.study.enums.FileStorageModuleNameEnum.HOMEWORK_NAME;

@Service
@Slf4j
public class HomeworkService {

    @Autowired
    private HomeworkMapper homeworkMapper;
    @Autowired
    private HomeworkSubmissionMapper submissionMapper;
    @Autowired
    private AttachmentMapper attachmentMapper;
    @Autowired
    private FileStorageService fileStorageService;
    @Autowired
    private AsyncCleanupFileObjService cleanupFileObjService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserInfoUtils userInfoUtils;

    @Autowired
    private ClassMemberMapper classMemberMapper;

    public static final String HOMEWORK_ATTACHMENT_OWNER_TYPE = "homework";
    public static final String SUBMISSION_ATTACHMENT_OWNER_TYPE = "submission";

    // --- 教师功能 ---

    /**
     * 教师发布作业
     * 权限：目前仅有教师可以发布作业
     */
    @Transactional
    public HomeworkDetail createHomework(HomeworkCreateDTO dto, List<MultipartFile> smallFiles) throws IOException {
        Assert.isTrue(userInfoUtils.currentUserInClassIsTeacher(), "当前登录用户不是教师");
        Long currentUserId = AuthenticationUserUtil.getCurrentUserId();

        Homework homework = new Homework();
        homework.setTeacherId(currentUserId);
        homework.setTitle(dto.getTitle());
        homework.setContent(dto.getContent());
        homeworkMapper.insert(homework);

        handleAttachment(homework.getId(), HOMEWORK_ATTACHMENT_OWNER_TYPE,
                currentUserId, dto.getAttachmentUploadIds(), smallFiles);

        // 4. 返回完整的作业信息 (包含附件)
        return homeworkMapper.findById(homework.getId());
    }

    /**
     * 修改作业
     * 权限：管理员、校长、教师，与作业发布者保持一致
     */
    @Transactional
    public HomeworkDetail updateHomework(Long homeworkId, HomeworkUpdateDTO dto, List<MultipartFile> smallFiles) throws IOException {
        HomeworkDetail originalHomework = homeworkMapper.findById(homeworkId);
        validateTeacherPermission(originalHomework.getTeacherId());

        Homework homeworkToUpdate = new Homework();
        homeworkToUpdate.setId(homeworkId);
        homeworkToUpdate.setTitle(dto.getTitle());
        homeworkToUpdate.setContent(dto.getContent());
        int i = homeworkMapper.updateById(homeworkToUpdate);
        Assert.isTrue(i > 0, "更新作业失败");

        if (!CollectionUtils.isEmpty(dto.getAttachmentIdsToDelete())) {
            List<AttachmentDetail> attachmentsToDelete = attachmentMapper.findByIds(dto.getAttachmentIdsToDelete());
            List<String> filePathsToDelete = attachmentsToDelete.stream()
                    .map(AttachmentDetail::getFilePath)
                    .collect(Collectors.toList());

            attachmentMapper.deleteBatchIds(dto.getAttachmentIdsToDelete());

            cleanupFileObjService.cleanup(filePathsToDelete);
        }

        handleAttachment(homeworkId, HOMEWORK_ATTACHMENT_OWNER_TYPE,
                originalHomework.getTeacherId(), dto.getAttachmentUploadIds(), smallFiles);

        List<Long> collect = submissionMapper.findAllByHomeworkId(homeworkId).stream()
                .map(HomeworkSubmission::getId)
                .collect(Collectors.toList());
        if (!CollectionUtils.isEmpty(collect)) {
            submissionMapper.batchUpdateStatus(collect,
                    HomeworkSubmissionStatusEnum.HAVE_UPDATED.getValue());
        }
        return homeworkMapper.findById(homeworkId);
    }

    /**
     * 修改作业提交
     * 权限：仅学生本人
     */
    @Transactional
    public HomeworkSubmissionDetail updateHomeworkSubmission(Long homeworkSubmissionId, HomeworkSubmissionUpdateDTO dto,
                                                             List<MultipartFile> smallFiles) throws IOException {
        HomeworkSubmissionDetail origin = submissionMapper.findById(homeworkSubmissionId);
        Assert.isTrue(HomeworkSubmissionStatusEnum.RETURNED.getValue().equals(origin.getStatus()) ||
                HomeworkSubmissionStatusEnum.HAVE_UPDATED.getValue().equals(origin.getStatus()), "作业提交状态错误");
        Assert.isTrue(Objects.equals(origin.getStudentId(), AuthenticationUserUtil.getCurrentUserId()), "你无权限操作他人作业");

        // 1. 更新作业提交主体信息
        HomeworkSubmission homeworkSubmission = new HomeworkSubmission();
        homeworkSubmission.setId(homeworkSubmissionId);
        homeworkSubmission.setContent(dto.getContent());
        homeworkSubmission.setStatus(HomeworkSubmissionStatusEnum.RE_SUBMITTED.getValue());
        int i = submissionMapper.updateById(homeworkSubmission);
        Assert.isTrue(i > 0, "更新作业提交失败");

        // 2. 处理要删除的旧附件
        if (!CollectionUtils.isEmpty(dto.getAttachmentIdsToDelete())) {
            // 2.1 查找要删除的附件信息，以获取文件路径用于物理删除
            List<AttachmentDetail> attachmentsToDelete = attachmentMapper.findByIds(dto.getAttachmentIdsToDelete());
            List<String> filePathsToDelete = attachmentsToDelete.stream()
                    .map(AttachmentDetail::getFilePath)
                    .collect(Collectors.toList());

            // 2.2 从数据库中删除附件记录
            attachmentMapper.deleteBatchIds(dto.getAttachmentIdsToDelete());

            // 2.3 异步删除物理文件
            cleanupFileObjService.cleanup(filePathsToDelete);
        }

        handleAttachment(homeworkSubmissionId, SUBMISSION_ATTACHMENT_OWNER_TYPE,
                origin.getStudentId(), dto.getAttachmentUploadIds(), smallFiles);

        // 7. 返回更新后完整的作业信息
        return submissionMapper.findById(homeworkSubmissionId);
    }

    /**
     * 教师查看自己发布的所有作业
     * 权限：仅教师本人
     */
    public List<HomeworkDetail> getHomeworksByTeacher() {
        Assert.isTrue(userInfoUtils.currentUserInClassIsTeacher(), "当前登录用户不是教师");

        return homeworkMapper.findAllByTeacherId(AuthenticationUserUtil.getCurrentUserId());
    }

    /**
     * 查看某一个作业
     * 权限：管理员、校长、教师、学生，与作业发布者保持一致
     */
    public HomeworkDetail getHomeworkById(Long homeworkId) {
        HomeworkDetail result = homeworkMapper.findById(homeworkId);
        validateStudentPermission(result.getTeacherId());

        return result;
    }

    /**
     * 打回作业提交
     * 权限：管理员、校长、教师，与作业提交对应的作业发布者保持一致
     */
    public HomeworkSubmissionDetail returnSubmission(Long submissionId) {
        HomeworkSubmissionDetail submissionDetail = submissionMapper.findById(submissionId);
        Long teacherId = submissionDetail.getHomework().getTeacherId();
        validateTeacherPermission(teacherId);

        submissionMapper.batchUpdateStatus(List.of(submissionDetail.getId()),
                HomeworkSubmissionStatusEnum.RETURNED.getValue());

        return submissionMapper.findById(submissionId);
    }

    // --- 学生功能 ---

    /**
     * 学生提交作业
     * 权限：学生，与作业发布者保持一致
     */
    @Transactional
    public HomeworkSubmissionDetail createSubmission(SubmissionCreateDTO dto, List<MultipartFile> smallFiles) throws IOException {
        Long currentUserId = AuthenticationUserUtil.getCurrentUserId();
        Assert.isTrue(userInfoUtils.currentUserInClassIsStudent(), "当前登录用户不是学生");
        HomeworkDetail homeworkDetail = homeworkMapper.findById(dto.getHomeworkId());
        validateStudentPermission(homeworkDetail.getTeacherId());

        // 校验是否重复提交
        HomeworkSubmissionDetail existing = submissionMapper.findByHomeworkIdAndStudentId(dto.getHomeworkId(), currentUserId);
        if (existing != null) {
            throw new IllegalStateException("You have already submitted this homework.");
        }

        // 1. 创建并插入提交主体
        HomeworkSubmission submission = new HomeworkSubmission();
        submission.setHomeworkId(dto.getHomeworkId());
        submission.setStudentId(currentUserId);
        submission.setContent(dto.getContent());
        submission.setStatus(HomeworkSubmissionStatusEnum.SUBMITTED.getValue());
        submissionMapper.insert(submission);

        handleAttachment(submission.getId(), SUBMISSION_ATTACHMENT_OWNER_TYPE,
                currentUserId, dto.getAttachmentUploadIds(), smallFiles);

        // 4. 返回完整的提交信息 (包含附件和作业信息)
        return submissionMapper.findById(submission.getId());
    }

    /**
     * 学生查看自己所有的提交记录
     * 权限：仅学生本人
     */
    public List<HomeworkSubmissionDetail> getSubmissionsByStudent() {
        Assert.isTrue(userInfoUtils.currentUserInClassIsStudent(), "当前登录用户不是学生");

        Long currentUserId = AuthenticationUserUtil.getCurrentUserId();
        return submissionMapper.findAllByStudentId(currentUserId);
    }

    /**
     * 学生查看自己某个作业的提交
     * 权限：仅学生本人
     */
    public HomeworkSubmissionDetail getSubmissionByStudent(Long homeworkId) {
        Assert.isTrue(userInfoUtils.currentUserInClassIsStudent(), "当前登录用户不是学生");

        Long currentUserId = AuthenticationUserUtil.getCurrentUserId();

        return submissionMapper.findByHomeworkIdAndStudentId(homeworkId, currentUserId);
    }

    /**
     * 查看作业的所有提交记录
     * 权限：管理员、校长、教师，与作业发布者保持一致
     */
    public List<HomeworkSubmissionDetail> getHomeworkSubmissions(Long homeworkId) {
        HomeworkDetail homeworkDetail = homeworkMapper.findById(homeworkId);
        validateTeacherPermission(homeworkDetail.getTeacherId());

        return submissionMapper.findAllByHomeworkId(homeworkId);
    }

    /**
     * 学生查看所有作业列表（即学生所在班级的所有作业列表）
     * 权限：仅学生本人
     */
    @Transactional
    public List<HomeworkDetail> getAllHomeworksForStudent() {
        User user = userMapper.findByUsernameWithOrgInfo(AuthenticationUserUtil.getCurrentUserName());
        if (user == null || user.getClassMember() == null || !ClassesRoleEnum.STUDENT.getRole().equals(user.getClassMember().getRole())) {
            throw new IllegalStateException("当前登录用户不是学生");
        }

        return this.getAllHomeworksInClass(user.getClassMember().getClassId());
    }

    /**
     * 删除作业
     * 权限：管理员、校长、教师，与作业发布者保持一致
     */
    @Transactional
    public void deleteHomework(Long homeworkId) {
        // 1. 权限校验
        HomeworkDetail homework = homeworkMapper.findById(homeworkId);
        if (homework == null) {
            throw new IllegalArgumentException("Homework not found with id: " + homeworkId);
        }
        validateTeacherPermission(homework.getTeacherId());

        // 2. 查找所有关联的附件路径，以便后续删除物理文件
        List<String> filePathsToDelete = new ArrayList<>();

        // 2.1 查找作业本身的附件
        List<AttachmentDetail> homeworkAttachments = attachmentMapper.findByOwner(homeworkId, HOMEWORK_ATTACHMENT_OWNER_TYPE);
        homeworkAttachments.forEach(att -> filePathsToDelete.add(att.getFilePath()));

        // 2.2 查找所有提交记录及其附件
        List<Long> submissionIds = submissionMapper.findIdsByHomeworkId(homeworkId);
        if (submissionIds != null && !submissionIds.isEmpty()) {
            for (Long submissionId : submissionIds) {
                List<AttachmentDetail> submissionAttachments = attachmentMapper.findByOwner(submissionId, SUBMISSION_ATTACHMENT_OWNER_TYPE);
                submissionAttachments.forEach(att -> filePathsToDelete.add(att.getFilePath()));
            }
            // 批量删除提交记录的附件
            attachmentMapper.deleteByOwners(submissionIds, SUBMISSION_ATTACHMENT_OWNER_TYPE);
        }

        // 3. 在事务内删除数据库记录
        // 3.1 删除作业本身的附件
        attachmentMapper.deleteByOwner(homeworkId, HOMEWORK_ATTACHMENT_OWNER_TYPE);
        // 3.2 删除所有提交记录
        submissionMapper.deleteByHomeworkId(homeworkId);
        // 3.3 删除作业本身
        homeworkMapper.deleteById(homeworkId);

        // 4. 数据库事务成功后，删除物理文件
        // 这一步在事务外执行，因为文件系统操作不应影响数据库事务的回滚
        cleanupFileObjService.cleanup(filePathsToDelete);
    }

    /**
     * 查看某个班级的所有作业，本质上是查看某个班级的所有教师发布的作业
     * 权限：管理员、校长、教师
     */
    @Transactional
    public List<HomeworkDetail> getAllHomeworksInClass(Long classId) {
        return classMemberMapper.findUsersByClassIdAndRole(classId, ClassesRoleEnum.TEACHER.getRole())
                .stream()
                .flatMap(teacher -> this.innerGetHomeworksByTeacher(teacher.getUserId()).stream())
                .collect(Collectors.toList());
    }

    // --- 私有辅助方法 ---
    /**
     * 查看某一个教师发布的所有作业
     */
    private List<HomeworkDetail> innerGetHomeworksByTeacher(Long teacherId) {
        return homeworkMapper.findAllByTeacherId(teacherId);
    }

    private List<HomeworkDetail> innerGetAllHomeworksInClass(Long classId) {
        return classMemberMapper.findUsersByClassIdAndRole(classId, ClassesRoleEnum.TEACHER.getRole())
                .stream()
                .flatMap(teacher -> this.innerGetHomeworksByTeacher(teacher.getUserId()).stream())
                .collect(Collectors.toList());
    }


    /**
     * 上传文件并构建附件对象列表
     */
    private List<Attachment> uploadAndBuildAttachments(List<MultipartFile> files, Long ownerId, String ownerType, Long uploaderId) throws IOException {
        List<Attachment> attachments = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;

            try (InputStream inputStream = file.getInputStream()) {
                String originalFilename = file.getOriginalFilename();
                String filePath = HomeworkService.produceAttachPath(originalFilename);

                String contentType = file.getContentType();
                MimeTypeIdResult mimeTypeId = fileStorageService.getMimeTypeId(contentType);
                Assert.isTrue(mimeTypeId != null && Boolean.TRUE.equals(mimeTypeId.getSuccess()) &&
                        mimeTypeId.getMimeTypeId() != null, "Mime type not found: " + contentType);

                // 调用文件存储服务上传文件
                fileStorageService.createFile(filePath, inputStream, contentType);

                // 构建附件对象
                Attachment attachment = Attachment.builder()
                        .ownerId(ownerId)
                        .ownerType(ownerType)
                        .fileName(originalFilename)
                        .filePath(filePath)
                        .fileSize(file.getSize())
                        .mimeTypeId(mimeTypeId.getMimeTypeId())
                        .uploaderId(uploaderId)
                        .build();
                attachments.add(attachment);
            }
        }
        return attachments;
    }

    private void handleAttachment(Long ownerId, String ownerType, Long operator,
                                  List<String> attachmentIds, List<MultipartFile> smallFiles) throws IOException {
        List<Attachment> attachmentList = new ArrayList<>();
        // 2. 处理小附件
        if (!CollectionUtils.isEmpty(smallFiles)) {
            List<Attachment> attachments = uploadAndBuildAttachments(
                    smallFiles,
                    ownerId,
                    ownerType,
                    operator
            );
            attachmentList.addAll(attachments);
        }

        // 大附件处理
        if (!CollectionUtils.isEmpty(attachmentIds)) {
            List<Attachment> attachments = attachmentIds.stream()
                    .map(uploadId -> {
                        UploadInfoRedisDTO uploadInfo = (UploadInfoRedisDTO) redisTemplate.opsForValue()
                                .get(UPLOAD_ID_PREFIX + uploadId);
                        // Note: 忽略为空的
                        if (uploadInfo == null) return null;
                        MimeTypeIdResult mimeTypeId = fileStorageService.getMimeTypeId(uploadInfo.getMimeTypeName());
                        Assert.isTrue(mimeTypeId != null && Boolean.TRUE.equals(mimeTypeId.getSuccess()),
                                "Mime type not found: " + uploadInfo.getMimeTypeName());
                        return Attachment.builder()
                                .ownerId(ownerId)
                                .ownerType(ownerType)
                                .fileName(uploadInfo.getFileName())
                                .filePath(uploadInfo.getFilePath())
                                .fileSize(uploadInfo.getFileSize())
                                .mimeTypeId(mimeTypeId.getMimeTypeId())
                                .uploaderId(operator)
                                .build();
                    }).filter(Objects::nonNull).collect(Collectors.toList());
            attachmentList.addAll(attachments);
        }

        if (!CollectionUtils.isEmpty(attachmentList)) {
            attachmentMapper.batchInsert(attachmentList);
        }
    }

    public static String produceAttachPath(String fileName) {
        String fileExtension = "";
        if (fileName != null && fileName.contains(".")) {
            fileExtension = fileName.substring(fileName.lastIndexOf("."));
        }
        String uniqueFileName = UUID.randomUUID().toString(true) + fileExtension;
        return HOMEWORK_NAME.getModuleName() + "/" + uniqueFileName;
    }

    // 验证当前登录用户与targetId(教师/学生)是否是管理员、校长、同一班级的教师
    private void validateTeacherPermission(Long targetId) {
        // 管理员
        if (AuthenticationUserUtil.currentUserIsAdmin()) {
            return;
        }
        User user = userInfoUtils.getCurrentUserInfoWithOrgInfo();
        if (user == null)
            throw new IllegalStateException("当前无登录账户");
        User target = userMapper.findByIdWithOrgInfo(targetId);
        if (target == null || target.getClassMember() == null)
            throw new IllegalStateException("目标教师不存在");

        // 校长
        if (user.getSchoolMember() != null && SchoolRoleEnum.PRINCIPAL.getRole().equals(user.getSchoolMember().getRole())) {
            if (Objects.equals(user.getSchoolMember().getSchoolId(),
                    target.getClassMember().getClasses().getSchoolId()))
                return;
        }
        // 同班级的教师
        if (user.getClassMember() != null && ClassesRoleEnum.TEACHER.getRole().equals(user.getClassMember().getRole())) {
            if (Objects.equals(user.getClassMember().getClassId(),
                    target.getClassMember().getClassId()))
                return;
        }
        throw new IllegalStateException("你无权限操作");
    }

    // 验证当前登录用户与targetId(教师/学生)是否是管理员、校长、同一班级的教师/学生
    private void validateStudentPermission(Long targetId) {
        // 管理员
        if (AuthenticationUserUtil.currentUserIsAdmin()) {
            return;
        }
        User user = userInfoUtils.getCurrentUserInfoWithOrgInfo();
        if (user == null)
            throw new IllegalStateException("当前无登录账户");
        User target = userMapper.findByIdWithOrgInfo(targetId);
        if (target == null || target.getClassMember() == null)
            throw new IllegalStateException("目标教师不存在");

        // 校长
        if (user.getSchoolMember() != null && SchoolRoleEnum.PRINCIPAL.getRole().equals(user.getSchoolMember().getRole())) {
            if (Objects.equals(user.getSchoolMember().getSchoolId(),
                    target.getClassMember().getClasses().getSchoolId()))
                return;
        }
        // 同班级
        if (user.getClassMember() != null) {
            if (Objects.equals(user.getClassMember().getClassId(),
                    target.getClassMember().getClassId()))
                return;
        }
        throw new IllegalStateException("你无权限操作");
    }
}