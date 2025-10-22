package com.kwang.study.homework.service;

import com.kwang.study.auth.mapper.UserMapper;
import com.kwang.study.auth.pojo.User;
import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.fs.dto.result.MimeTypeIdResult;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.homework.dto.request.HomeworkCreateDTO;
import com.kwang.study.homework.dto.request.SubmissionCreateDTO;
import com.kwang.study.homework.dto.request.UploadInfoRedisDTO;
import com.kwang.study.homework.pojo.*;
import com.kwang.study.homework.mapper.AttachmentMapper;
import com.kwang.study.homework.mapper.HomeworkMapper;
import com.kwang.study.homework.mapper.HomeworkSubmissionMapper;
import com.kwang.study.homework.service.async.AsyncCleanupFileObjService;
import com.kwang.study.organization.pojo.ClassMember;
import com.kwang.study.organization.service.ClassMemberService;
import com.kwang.study.organization.service.ClassesService;
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
    private ClassMemberService classMemberService;

    @Autowired
    private UserMapper userMapper;

    public static final String HOMEWORK_ATTACHMENT_OWNER_TYPE = "homework";
    public static final String SUBMISSION_ATTACHMENT_OWNER_TYPE = "submission";

    // --- 教师功能 ---

    /**
     * 教师发布作业
     * @param dto 作业创建数据传输对象
     * @param smallFiles 小附件列表
     * @return 创建的作业对象
     * @throws IOException 文件IO异常
     */
    @Transactional
    public HomeworkDetail createHomework(HomeworkCreateDTO dto, List<MultipartFile> smallFiles) throws IOException {
        // 1. 创建并插入作业主体
        Homework homework = new Homework();
        homework.setTeacherId(dto.getTeacherId()); // 实际应从用户登录信息中获取
        homework.setTitle(dto.getTitle());
        homework.setContent(dto.getContent());
        homeworkMapper.insert(homework);

        List<Attachment> attachmentList = new ArrayList<>();
        // 2. 处理小附件
        if (!CollectionUtils.isEmpty(smallFiles)) {
            List<Attachment> attachments = uploadAndBuildAttachments(
                    smallFiles,
                    homework.getId(),
                    HOMEWORK_ATTACHMENT_OWNER_TYPE,
                    dto.getTeacherId()
            );
            attachmentList.addAll(attachments);
        }

        // 大附件处理
        List<String> attachmentIds = dto.getAttachmentUploadIds();
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
                                .ownerId(homework.getId())
                                .ownerType(HOMEWORK_ATTACHMENT_OWNER_TYPE)
                                .fileName(uploadInfo.getFileName())
                                .filePath(uploadInfo.getFilePath())
                                .fileSize(uploadInfo.getFileSize())
                                .mimeTypeId(mimeTypeId.getMimeTypeId())
                                .uploaderId(dto.getTeacherId())
                                .build();
                    }).filter(Objects::nonNull).collect(Collectors.toList());
            attachmentList.addAll(attachments);
        }

        if (!CollectionUtils.isEmpty(attachmentList)) {
            attachmentMapper.batchInsert(attachmentList);
        }

        // 4. 返回完整的作业信息 (包含附件)
        return homeworkMapper.findById(homework.getId());
    }

    /**
     * 教师查看自己发布的所有作业
     * @param teacherId 教师ID
     * @return 作业列表
     */
    public List<HomeworkDetail> getHomeworksByTeacher(Long teacherId) {
        return homeworkMapper.findAllByTeacherId(teacherId);
    }

    /**
     * 查看某一个作业
     * @param homeworkId 作业ID
     * @return 作业对象
     */
    public HomeworkDetail getHomeworkById(Long homeworkId) {
        return homeworkMapper.findById(homeworkId);
    }

    // --- 学生功能 ---

    /**
     * 学生提交作业
     * @param dto 提交创建数据传输对象
     * @param smallFiles 小附件列表
     * @return 创建的提交记录
     * @throws IOException 文件IO异常
     */
    @Transactional
    public HomeworkSubmissionDetail createSubmission(SubmissionCreateDTO dto, List<MultipartFile> smallFiles) throws IOException {
        // 校验是否重复提交
        HomeworkSubmissionDetail existing = submissionMapper.findByHomeworkIdAndStudentId(dto.getHomeworkId(), dto.getStudentId());
        if (existing != null) {
            throw new IllegalStateException("You have already submitted this homework.");
        }

        // 1. 创建并插入提交主体
        HomeworkSubmission submission = new HomeworkSubmission();
        submission.setHomeworkId(dto.getHomeworkId());
        submission.setStudentId(dto.getStudentId());
        submission.setContent(dto.getContent());
        submissionMapper.insert(submission);

        List<Attachment> attachmentList = new ArrayList<>();
        // 2. 处理小附件
        if (!CollectionUtils.isEmpty(smallFiles)) {
            List<Attachment> attachments = uploadAndBuildAttachments(
                    smallFiles,
                    submission.getId(),
                    SUBMISSION_ATTACHMENT_OWNER_TYPE,
                    dto.getStudentId()
            );
            attachmentList.addAll(attachments);
        }

        // 大附件处理
        List<String> attachmentIds = dto.getAttachmentUploadIds();
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
                                .ownerId(submission.getId())
                                .ownerType(SUBMISSION_ATTACHMENT_OWNER_TYPE)
                                .fileName(uploadInfo.getFileName())
                                .filePath(uploadInfo.getFilePath())
                                .fileSize(uploadInfo.getFileSize())
                                .mimeTypeId(mimeTypeId.getMimeTypeId())
                                .uploaderId(dto.getStudentId())
                                .build();
                    }).filter(Objects::nonNull).collect(Collectors.toList());
            attachmentList.addAll(attachments);
        }

        if (!CollectionUtils.isEmpty(attachmentList)) {
            attachmentMapper.batchInsert(attachmentList);
        }

        // 4. 返回完整的提交信息 (包含附件和作业信息)
        return submissionMapper.findById(submission.getId());
    }

    /**
     * 学生查看自己所有的提交记录
     * @param studentId 学生ID
     * @return 提交记录列表
     */
    public List<HomeworkSubmissionDetail> getSubmissionsByStudent(Long studentId) {
        return submissionMapper.findAllByStudentId(studentId);
    }

    public HomeworkSubmissionDetail getSubmissionByStudent(Long studentId, Long homeworkId) {
        return submissionMapper.findByHomeworkIdAndStudentId(homeworkId, studentId);
    }

    /**
     * 教师查看作业的所有提交记录
     * @param homeworkId 作业ID
     * @return 提交记录列表
     */
    public List<HomeworkSubmissionDetail> getHomeworkSubmissions(Long homeworkId) {
        return submissionMapper.findAllByHomeworkId(homeworkId);
    }

    /**
     * 学生查看所有作业列表（即学生所在班级的所有作业列表）
     * @return 作业列表
     */
    @Transactional
    public List<HomeworkDetail> getAllHomeworksForStudent(String username) {
        User user = userMapper.findByUsernameWithClasses(username);
        return this.getAllHomeworksInClass(user.getClassMember().getClassId());
    }

    /**
     * 教师删除作业
     * @param homeworkId 作业ID
     */
    @Transactional
    public void deleteHomework(Long homeworkId) {
        // 1. 权限校验
        Homework homework = homeworkMapper.findById(homeworkId);
        if (homework == null) {
            throw new IllegalArgumentException("Homework not found with id: " + homeworkId);
        }

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
     */
    @Transactional
    public List<HomeworkDetail> getAllHomeworksInClass(Long classId) {
        return classMemberService.getTeachersInClass(classId)
                .stream()
                .flatMap(teacher -> this.getHomeworksByTeacher(teacher.getId()).stream())
                .collect(Collectors.toList());
    }

    // --- 私有辅助方法 ---

    /**
     * 上传文件并构建附件对象列表
     * @param files MultipartFile 列表
     * @param ownerId 所属对象ID
     * @param ownerType 所属对象类型
     * @param uploaderId 上传者ID
     * @return Attachment 列表
     * @throws IOException IO异常
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

    public static String produceAttachPath(String fileName) {
        String fileExtension = "";
        if (fileName != null && fileName.contains(".")) {
            fileExtension = fileName.substring(fileName.lastIndexOf("."));
        }
        String uniqueFileName = UUID.randomUUID().toString(true) + fileExtension;
        return HOMEWORK_NAME.getModuleName() + "/" + uniqueFileName;
    }
}