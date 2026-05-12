package com.kwang.study.homework.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.course.mapper.CourseMapper;
import com.kwang.study.course.pojo.Course;
import com.kwang.study.fs.dto.result.MimeTypeIdResult;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.homework.dto.json.HomeworkMetaDTO;
import com.kwang.study.homework.dto.json.QuestionItemDTO;
import com.kwang.study.homework.dto.json.SubmissionGradingDTO;
import com.kwang.study.homework.dto.request.*;
import com.kwang.study.homework.enums.HomeworkSubmissionStatusEnum;
import com.kwang.study.homework.pojo.*;
import com.kwang.study.homework.mapper.AttachmentMapper;
import com.kwang.study.homework.mapper.HomeworkMapper;
import com.kwang.study.homework.mapper.HomeworkSubmissionMapper;
import com.kwang.study.homework.service.async.AsyncCleanupFileObjService;
import com.kwang.study.llm.config.LLMGlobalConfig;
import com.kwang.study.llm.core.LLM;
import com.kwang.study.llm.core.LLMContext;
import com.kwang.study.llm.core.Prompt;
import com.kwang.study.llm.core.Tools;
import com.kwang.study.llm.dto.request.ChatRequestDTO;
import com.kwang.study.llm.service.RAG;
import com.kwang.study.organization.enums.ClassesRoleEnum;
import com.kwang.study.organization.pojo.ClassMember;
import com.openai.models.chat.completions.ChatCompletionMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
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
import java.util.*;
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
    private UserInfoUtils userInfoUtils;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private HomeworkValidator homeworkValidator;

    @Autowired
    private CourseMapper courseMapper;

    @Autowired
    private RAG rag;

    public static final String HOMEWORK_ATTACHMENT_OWNER_TYPE = "homework";
    public static final String SUBMISSION_ATTACHMENT_OWNER_TYPE = "submission";

    // --- 教师功能 ---

    /**
     * 教师发布作业
     * 权限：目前仅有教师可以发布作业
     */
    @Transactional
    public HomeworkDetail createHomework(HomeworkCreateDTO dto, List<MultipartFile> smallFiles) throws IOException {
        ClassMember activeCM = userInfoUtils.getCurrentActiveClassMember();
        Assert.isTrue(activeCM != null && ClassesRoleEnum.TEACHER.getRole().equals(activeCM.getRole()), "请先切换到教师身份再发布作业");
        Long currentUserId = AuthenticationUserUtil.getCurrentUserId();

        Course course = courseMapper.findById(dto.getCourseId());
        Assert.notNull(course, "所选课程不存在");
        Assert.isTrue(course.getClassId().equals(activeCM.getClassId()), "该课程不属于当前班级");

        Homework homework = new Homework();
        homework.setTeacherId(currentUserId);
        homework.setClassId(activeCM.getClassId());
        homework.setCourseId(dto.getCourseId());
        homework.setTitle(dto.getTitle());
        homework.setContent(dto.getContent());

        homework.setType(dto.getType());
        if ("STRUCTURED".equals(dto.getType()) && dto.getMetaData() != null) {
            // 1. Map -> POJO
            HomeworkMetaDTO meta = objectMapper.convertValue(dto.getMetaData(), HomeworkMetaDTO.class);
            // 2. 校验
            homeworkValidator.validateHomeworkMeta(meta);
            // 3. 序列化存储
            homework.setMetaData(objectMapper.writeValueAsString(meta));
        }
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
        validateTeacherOperation(originalHomework.getClassId(), originalHomework.getTeacherId());

        Homework homeworkToUpdate = new Homework();
        homeworkToUpdate.setId(homeworkId);
        homeworkToUpdate.setTitle(dto.getTitle());
        homeworkToUpdate.setContent(dto.getContent());

        homeworkToUpdate.setType(dto.getType());
        if ("STRUCTURED".equals(dto.getType()) && dto.getMetaData() != null) {
            homeworkToUpdate.setMetaData(objectMapper.writeValueAsString(dto.getMetaData()));
        }

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
        return (HomeworkSubmissionDetail) desensitization(submissionMapper.findById(homeworkSubmissionId));
    }

    /**
     * 教师查看自己发布的所有作业
     * 权限：仅教师本人
     */
    public List<HomeworkDetail> getHomeworksByTeacher() {
        ClassMember activeCM = userInfoUtils.getCurrentActiveClassMember();
        Assert.isTrue(activeCM != null && ClassesRoleEnum.TEACHER.getRole().equals(activeCM.getRole()), "身份错误");

        return homeworkMapper.findAllByTeacherIdAndClassId(AuthenticationUserUtil.getCurrentUserId(), activeCM.getClassId());
    }

    /**
     * 查看某一个作业
     * 权限：管理员、校长、教师、学生，与作业发布者保持一致
     */
    public HomeworkDetail getHomeworkById(Long homeworkId) {
        HomeworkDetail result = homeworkMapper.findById(homeworkId);
        validateAccess(result.getClassId(), result.getCourseId());

        return (HomeworkDetail) desensitization(result);
    }

    /**
     * 查看某课程下的所有作业
     * 权限：管理员、校长、教师、学生，与作业发布者保持一致
     */
    public List<HomeworkDetail> getHomeworksByCourseId(Long courseId) {
        Course course = courseMapper.findById(courseId);
        Assert.notNull(course, "课程不存在");
        validateAccess(course.getClassId(), courseId); // 权限复用：能在该班级就有权限看
        return homeworkMapper.findAllByCourseId(courseId);
    }

    /**
     * 打回作业提交
     * 权限：管理员、校长、教师，与作业提交对应的作业发布者保持一致
     */
    public HomeworkSubmissionDetail returnSubmission(Long submissionId) {
        HomeworkSubmissionDetail submissionDetail = submissionMapper.findById(submissionId);
        Long teacherId = submissionDetail.getHomework().getTeacherId();
        validateTeacherOperation(submissionDetail.getClassId(), teacherId);

        Assert.isTrue(!HomeworkSubmissionStatusEnum.GRADED.getValue().equals(submissionDetail.getStatus()), "已批改的作业无法退回");

        submissionMapper.batchUpdateStatus(List.of(submissionDetail.getId()),
                HomeworkSubmissionStatusEnum.RETURNED.getValue());

        return submissionMapper.findById(submissionId);
    }

    // --- 学生功能 ---

    /**
     * 学生提交作业
     * 权限：学生，与作业发布者保持一致
     * 访客也不能提交作业
     */
    @Transactional
    public HomeworkSubmissionDetail createSubmission(SubmissionCreateDTO dto, List<MultipartFile> smallFiles) throws IOException {
        Long currentUserId = AuthenticationUserUtil.getCurrentUserId();
        ClassMember activeCM = userInfoUtils.getCurrentActiveClassMember();
        Assert.isTrue(activeCM != null && ClassesRoleEnum.STUDENT.getRole().equals(activeCM.getRole()), "身份错误");

        HomeworkDetail homeworkDetail = homeworkMapper.findById(dto.getHomeworkId());
        validateAccess(homeworkDetail.getClassId(), homeworkDetail.getCourseId());

        // 校验是否重复提交
        HomeworkSubmissionDetail existing = submissionMapper.findByHomeworkIdAndStudentId(dto.getHomeworkId(), currentUserId);
        if (existing != null) {
            throw new IllegalStateException("You have already submitted this homework.");
        }

        // 1. 创建并插入提交主体
        HomeworkSubmission submission = new HomeworkSubmission();
        submission.setHomeworkId(dto.getHomeworkId());
        submission.setStudentId(currentUserId);
        submission.setClassId(activeCM.getClassId());
        submission.setContent(dto.getContent());
        submission.setStatus(HomeworkSubmissionStatusEnum.SUBMITTED.getValue());

        if ("STRUCTURED".equals(homeworkDetail.getType()) && dto.getAnswerData() != null) {
            // 校验提交数据
            homeworkValidator.validateSubmission(homeworkDetail.getMetaData(), dto.getAnswerData());
            submission.setAnswerData(objectMapper.writeValueAsString(dto.getAnswerData()));
        }

        submissionMapper.insert(submission);

        handleAttachment(submission.getId(), SUBMISSION_ATTACHMENT_OWNER_TYPE,
                currentUserId, dto.getAttachmentUploadIds(), smallFiles);

        // 4. 返回完整的提交信息 (包含附件和作业信息)
        return (HomeworkSubmissionDetail) desensitization(submissionMapper.findById(submission.getId()));
    }

    /**
     * 学生查看自己所有的提交记录
     * 权限：仅学生本人
     */
    public List<HomeworkSubmissionDetail> getSubmissionsByStudent() {
        ClassMember activeCM = userInfoUtils.getCurrentActiveClassMember();
        Assert.isTrue(activeCM != null && ClassesRoleEnum.STUDENT.getRole().equals(activeCM.getRole()), "身份错误");

        Long currentUserId = AuthenticationUserUtil.getCurrentUserId();
        return submissionMapper.findAllByStudentIdAndClassId(currentUserId, activeCM.getClassId()).stream()
                .map(d -> (HomeworkSubmissionDetail) desensitization(d))
                .collect(Collectors.toList());
    }

    /**
     * 学生查看自己某个作业的提交
     * 权限：仅学生本人
     */
    public HomeworkSubmissionDetail getSubmissionByStudent(Long homeworkId) {
        ClassMember activeCM = userInfoUtils.getCurrentActiveClassMember();
        Assert.isTrue(activeCM != null && ClassesRoleEnum.STUDENT.getRole().equals(activeCM.getRole()), "身份错误");

        Long currentUserId = AuthenticationUserUtil.getCurrentUserId();

        return (HomeworkSubmissionDetail) desensitization(submissionMapper.findByHomeworkIdAndStudentId(homeworkId, currentUserId));
    }

    /**
     * 查看作业的所有提交记录
     * 权限：管理员、校长、教师，与作业发布者保持一致
     */
    public List<HomeworkSubmissionDetail> getHomeworkSubmissions(Long homeworkId) {
        HomeworkDetail homeworkDetail = homeworkMapper.findById(homeworkId);
        validateTeacherOperation(homeworkDetail.getClassId(), homeworkDetail.getTeacherId());

        return submissionMapper.findAllByHomeworkId(homeworkId);
    }

    /**
     * 学生查看所有作业列表（即学生所在班级的所有作业列表）
     * 权限：仅学生本人，以及访客（过滤）
     */
    @Transactional
    public List<HomeworkDetail> getAllHomeworksForStudent() {
        ClassMember activeCM = userInfoUtils.getCurrentActiveClassMember();
        if (activeCM == null ||
                (ClassesRoleEnum.TEACHER.getRole().equals(activeCM.getRole()))) {
            throw new IllegalStateException("请切换到学生身份");
        }

        List<HomeworkDetail> list = homeworkMapper.findAllByClassId(activeCM.getClassId());

        // === 新增：如果是访客，过滤掉无权访问的课程的作业 ===
        if (ClassesRoleEnum.GUEST.getRole().equals(activeCM.getRole())) {
            List<Long> allowed = activeCM.getAllowedCourseIds() == null ? List.of() : activeCM.getAllowedCourseIds();
            list = list.stream().filter(h -> allowed.contains(h.getCourseId())).collect(Collectors.toList());
        }

        return list.stream()
                .map(h -> (HomeworkDetail) desensitization(h))
                .collect(Collectors.toList());
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
        validateTeacherOperation(homework.getClassId(), homework.getTeacherId());

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
     * 查看某个班级的所有作业
     * 权限：管理员、校长、教师
     */
    @Transactional
    public List<HomeworkDetail> getAllHomeworksInClass(Long classId) {
        if (!(AuthenticationUserUtil.currentUserIsAdmin()
                || userInfoUtils.inClassOfSchoolPrincipal(classId)
                || userInfoUtils.inClassTeacher(classId)))
            throw new IllegalArgumentException("身份错误");

        return homeworkMapper.findAllByClassId(classId);
    }

    /**
     * 教师批改作业
     * 权限：管理员、校长、教师（与作业发布者一致）
     */
    @Transactional
    public HomeworkSubmissionDetail gradeSubmission(SubmissionGradingDTO dto) {
        HomeworkSubmissionDetail submission = submissionMapper.findById(dto.getSubmissionId());
        Assert.notNull(submission, "提交记录不存在");
        Assert.isTrue(Objects.equals(submission.getStatus(), HomeworkSubmissionStatusEnum.SUBMITTED.getValue()) ||
                Objects.equals(submission.getStatus(), HomeworkSubmissionStatusEnum.RE_SUBMITTED.getValue()) ||
                Objects.equals(submission.getStatus(), HomeworkSubmissionStatusEnum.GRADED.getValue()),
                "作业未提交，请提交作业");

        Homework homework = submission.getHomework();
        validateTeacherOperation(homework.getClassId(), homework.getTeacherId());

        // 3. 计算总分并构建 JSON
        Integer totalScore = dto.getManualTotalScore();
        String gradingDataJson = "{}";

        if ("STRUCTURED".equals(homework.getType()) && dto.getDetails() != null) {
            // 结构化作业：累加每一题的分数
            totalScore = 0;
            for (SubmissionGradingDTO.QuestionGradingItem item : dto.getDetails().values()) {
                if (item.getScore() != null) {
                    totalScore += item.getScore();
                }
            }
            try {
                gradingDataJson = objectMapper.writeValueAsString(dto);
            } catch (Exception e) {
                log.error("JSON serialization failed", e);
            }
        } else if ("SIMPLE".equals(homework.getType())) {
            Assert.isTrue(totalScore <= 100, "简单作业得分不可以超过100");
        }

        // 4. 更新对象
        HomeworkSubmission updateEntity = new HomeworkSubmission();
        updateEntity.setId(submission.getId());
        updateEntity.setScore(totalScore);
        updateEntity.setGradingData(gradingDataJson);
        updateEntity.setStatus(HomeworkSubmissionStatusEnum.GRADED.getValue());

        submissionMapper.updateById(updateEntity);

        return submissionMapper.findById(submission.getId());
    }
    /**
     * 查看某个作业提交
     * 权限：学生本人、相应教师、校长管理员
     */
    public HomeworkSubmissionDetail getSubmissionById(Long submissionId) {
        HomeworkSubmissionDetail submissionDetail = submissionMapper.findById(submissionId);
        validateAccess(submissionDetail.getClassId(), submissionDetail.getHomework().getCourseId());
        if (userInfoUtils.currentUserInClassIsStudent()) {
            Assert.isTrue(Objects.equals(AuthenticationUserUtil.getCurrentUserId(), submissionDetail.getStudentId()),
                    "学生查看非本人作业提交");
        }

        return (HomeworkSubmissionDetail) desensitization(submissionDetail);
    }

    /**
     * 学生查看某个课程内的所有本人提交
     */
    public List<HomeworkSubmissionDetail> getStudentAllSubmissionByCourseId(Long courseId) {
        Assert.isTrue(userInfoUtils.currentUserInClassIsStudent(), "当前角色不是学生");
        Long studentId = AuthenticationUserUtil.getCurrentUserId();
        Course course = courseMapper.findById(courseId);
        Assert.isTrue(userInfoUtils.inClassStudent(course.getClassId()), "当前角色不是目标课程的学生");

        return homeworkMapper.findAllByCourseId(courseId).stream()
                .map(homework -> submissionMapper.
                        findByHomeworkIdAndStudentId(homework.getId(), studentId))
                .collect(Collectors.toList());
    }


    @Autowired
    private com.kwang.study.llm.config.LLMGlobalConfig llmGlobalConfig;

    /**
     * AI 一键批改逻辑：统一将所有题目（客观题+主观题）交给大模型批改并生成评语
     * 权限：管理员、校长、教师（与作业发布者一致）
     */
    public SubmissionGradingDTO aiGradeSubmission(Long submissionId) {
        HomeworkSubmissionDetail submission = submissionMapper.findById(submissionId);
        Assert.notNull(submission, "提交记录不存在");

        HomeworkDetail homework = homeworkMapper.findById(submission.getHomeworkId());
        validateTeacherOperation(homework.getClassId(), homework.getTeacherId());
        Assert.isTrue("STRUCTURED".equals(homework.getType()), "仅支持结构化作业的 AI 批改");

        SubmissionGradingDTO resultDto = new SubmissionGradingDTO();
        resultDto.setSubmissionId(submissionId);
        Map<String, SubmissionGradingDTO.QuestionGradingItem> gradingDetails = new HashMap<>();
        int currentTotalScore = 0;

        try {
            HomeworkMetaDTO meta = objectMapper.readValue(homework.getMetaData(), HomeworkMetaDTO.class);
            // 将学生提交的 answerData 反序列化为 Map
            TypeReference<Map<String, Object>> typeRef = new TypeReference<>() {};
            Map<String, Object> answerMap = submission.getAnswerData() != null
                    ? objectMapper.readValue(submission.getAnswerData(), typeRef)
                    : new HashMap<>();

            // 1. 统一收集所有题目的信息，准备喂给大模型
            List<Map<String, Object>> tasksForLLM = new ArrayList<>();

            for (QuestionItemDTO q : meta.getQuestions()) {
                String qId = q.getId();
                Object studentAns = answerMap.get(qId);

                Map<String, Object> task = new HashMap<>();
                task.put("questionId", qId);
                task.put("type", q.getType()); // 告诉大模型是单选、多选还是简答
                task.put("title", q.getTitle());
                task.put("fullScore", q.getScore());

                // 选择题为了让大模型更好地写评语，最好把选项内容也给它
                if (q.getOptions() != null && !q.getOptions().isEmpty()) {
                    task.put("options", q.getOptions());
                }

                task.put("correctAnswer", q.getCorrectAnswer());
                task.put("aiGradingCriteria", q.getAiGradingCriteria());
                task.put("studentAnswer", studentAns != null ? studentAns : "未作答");

                tasksForLLM.add(task);
            }

            // 2. 调用大模型进行统批
            if (!tasksForLLM.isEmpty()) {
                LLMGlobalConfig.SceneConfig sceneConfig = llmGlobalConfig.getScenes()
                        .getOrDefault("homework-grading", llmGlobalConfig.getScenes().get("default"));

                // 更新提示词：明确要求大模型批改包括选择题在内的所有题目
                String sysPrompt = rag.build(ChatRequestDTO.builder().scene("homework-grading").build(), null);

                LLMContext context = LLMContext.builder()
                        .scene("homework-grading")
                        .llmConfig(sceneConfig)
                        .systemPrompt(sysPrompt)
                        .request(ChatRequestDTO.builder()
                                .requestId(UUID.randomUUID().toString())
                                .build())
                        .build();

                String taskJson = objectMapper.writeValueAsString(tasksForLLM);
                Prompt prompt = Prompt.create()
                        .addUser("这是学生的作答数据（JSON格式）：\n" + taskJson + "\n请务必调用 HomeworkGradingTool 给出所有题目的批改结果及总评。");

                LLM llm = LLM.create(context);
                ChatCompletionMessage message = llm.invoke(prompt, context, List.of(Tools.HomeworkGradingTool.class));

                // 3. 解析 LLM 返回的 Tool 数据
                List<Tools.Tool> tools = Tools.convert(message, List.of(Tools.HomeworkGradingTool.class));
                if (!tools.isEmpty() && tools.get(0) instanceof Tools.HomeworkGradingTool) {
                    Tools.HomeworkGradingTool llmResult = (Tools.HomeworkGradingTool) tools.get(0);

                    resultDto.setGeneralComment(llmResult.getGeneralComment());
                    if (llmResult.getDetails() != null) {
                        // 建立题目映射，方便查找原始题目信息
                        Map<String, QuestionItemDTO> questionMap = meta.getQuestions().stream()
                                .collect(Collectors.toMap(QuestionItemDTO::getId, q -> q));

                        for (Tools.HomeworkGradingTool.GradingDetail d : llmResult.getDetails()) {
                            SubmissionGradingDTO.QuestionGradingItem item = new SubmissionGradingDTO.QuestionGradingItem();
                            int finalScore = d.getScore() == null ? 0 : d.getScore();

                            // =========================================================
                            // TODO: 未来可以在此处进行客观题分值的硬校验
                            // 如果大模型出现“幻觉”（比如学生选错却给了满分），可以在这里强行纠正。
                            // 示例代码（目前不用执行，仅做设计预留）：
                            /*
                            com.kwang.study.homework.dto.json.QuestionItemDTO originalQ = questionMap.get(d.getQuestionId());
                            if (originalQ != null && ("SINGLE_CHOICE".equals(originalQ.getType()) || "MULTI_CHOICE".equals(originalQ.getType()))) {
                                Object studentAns = answerMap.get(d.getQuestionId());
                                boolean isCorrect = checkObjectiveAnswer(originalQ.getCorrectAnswer(), studentAns);
                                if (!isCorrect && finalScore > 0) {
                                    finalScore = 0; // 强行纠正为0分
                                    d.setComment(d.getComment() + " [系统已纠正评分误差]");
                                } else if (isCorrect && finalScore < originalQ.getScore()) {
                                    finalScore = originalQ.getScore(); // 强行纠正为满分
                                }
                            }
                            */
                            // =========================================================

                            item.setScore(finalScore);
                            item.setComment(d.getComment());
                            gradingDetails.put(d.getQuestionId(), item);
                            currentTotalScore += item.getScore();
                        }
                    }
                } else {
                    resultDto.setGeneralComment("AI 批改遇到异常，未能正确调用批改工具，请手动核对。");
                }
            } else {
                resultDto.setGeneralComment("该作业无题目，无法进行AI批改。");
            }

            resultDto.setDetails(gradingDetails);
            resultDto.setManualTotalScore(currentTotalScore);

        } catch (Exception e) {
            log.error("AI Auto grading failed", e);
            throw new RuntimeException("AI批改过程发生错误：" + e.getMessage());
        }

        return resultDto;
    }

    // --- 私有辅助方法 ---
    private Homework desensitization(Homework homeworkDetail) {
        if (homeworkDetail == null)
            return null;
        // 如果是学生，且是结构化作业，进行脱敏
        Homework result = null;
        try {
            result = homeworkDetail.getClass().getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new IllegalStateException("异常：" + e.getMessage());
        }
        BeanUtils.copyProperties(homeworkDetail, result);
        if (!userInfoUtils.currentUserInClassIsTeacher() && "STRUCTURED".equals(result.getType()) && result.getMetaData() != null) {
            try {
                JsonNode root = objectMapper.readTree(result.getMetaData());
                if (root.has("questions")) {
                    for (JsonNode node : root.get("questions")) {
                        if (node instanceof ObjectNode) {
                            ((ObjectNode) node).
                                    remove(Arrays.asList("correctAnswer", "analysis", "aiGradingCriteria"));
                        }
                    }
                }
                result.setMetaData(root.toString());
            } catch (Exception e) {
                log.error("脱敏失败", e);
                result.setMetaData("{}"); // 安全起见
            }
        }
        return result;
    }

    private HomeworkSubmission desensitization(HomeworkSubmission homeworkSubmission) {
        if (homeworkSubmission == null)
            return null;
        // 过滤规则
        // 1. 目前是，教师批改后，可查看到作业答案
        if (Objects.equals(HomeworkSubmissionStatusEnum.GRADED.getValue(), homeworkSubmission.getStatus())) {
            return homeworkSubmission;
        }

        HomeworkSubmission result = null;
        try {
            result = homeworkSubmission.getClass().getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new IllegalStateException("异常：" + e.getMessage());
        }
        BeanUtils.copyProperties(homeworkSubmission, result);
        result.setHomework(desensitization(result.getHomework()));
        return result;
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

    /**
     * 校验教师/管理侧操作权限
     * 逻辑：
     * 1. Admin 直接放行
     * 2. 校长：当前激活学校必须匹配资源所属班级的学校 ID
     * 3. 教师：当前激活班级必须匹配资源所属班级 ID
     */
    private void validateTeacherOperation(Long resourceClassId, Long resourceOwnerId) {
        if (AuthenticationUserUtil.currentUserIsAdmin()) return;

        if (userInfoUtils.inClassOfSchoolPrincipal(resourceClassId) || userInfoUtils.inClassTeacher(resourceClassId)) {
            return;
        }

        throw new IllegalStateException("您在当前选中的身份下无权操作此资源，请检查当前班级/学校是否正确");
    }

    /**
     * 校验资源访问权限 (通用)
     * 逻辑：
     * 1. Admin 直接放行
     * 2. 校长：激活学校匹配
     * 3. 教师/学生：激活班级必须匹配资源所属班级
     */
    private void validateAccess(Long resourceClassId, Long courseId) {
        // 核心改造：作业的查看权限现在与所属的课程权限严格绑定
        if (!userInfoUtils.canAccessCourse(courseId, resourceClassId)) {
            throw new IllegalStateException("您在当前选中的身份下无权访问此课程或班级的数据");
        }
    }
}