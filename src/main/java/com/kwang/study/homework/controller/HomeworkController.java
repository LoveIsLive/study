package com.kwang.study.homework.controller;

import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.common.R;
import com.kwang.study.homework.dto.request.HomeworkCreateDTO;
import com.kwang.study.homework.pojo.HomeworkDetail;
import com.kwang.study.homework.service.HomeworkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static com.kwang.study.constant.ApiPrefixConstant.HOMEWORK_BASE_PREFIX;

@RestController
@RequestMapping(HOMEWORK_BASE_PREFIX)
@RequiredArgsConstructor
@Validated
public class HomeworkController {

    private final HomeworkService homeworkService;
    private final UserInfoUtils userInfoUtils;

    // --- Teacher Endpoints ---

    /**
     * 教师发布作业
     * 使用 multipart/form-data 格式提交
     * 表单字段：title, content
     * 文件字段：files
     */
    @PostMapping("/publish")
    public ResponseEntity<R<HomeworkDetail>> publishHomework(@Valid @RequestPart("dto") HomeworkCreateDTO dto,
                                                             @RequestPart(value = "files", required = false) List<MultipartFile> smallFiles) throws IOException {
        // NOTE: 目前只有教师才可以操作，管理员是否有权限待商榷
        if (!userInfoUtils.currentUserInClassIsTeacher())
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);

        // 获取认证身份
        dto.setTeacherId(AuthenticationUserUtil.getCurrentUserId());
        HomeworkDetail createdHomework = homeworkService.createHomework(dto, smallFiles);
        return ResponseEntity.ok(R.success(createdHomework));
    }

    /**
     * 教师查看自己发布的作业列表
     */
    @GetMapping("/teacher/all")
    public ResponseEntity<R<List<HomeworkDetail>>> getTeacherHomeworks() {
        if (!userInfoUtils.currentUserInClassIsTeacher())
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);

        Long teacherId = AuthenticationUserUtil.getCurrentUserId();
        List<HomeworkDetail> homeworks = homeworkService.getHomeworksByTeacher(teacherId);
        return ResponseEntity.ok(R.success(homeworks));
    }

    /**
     * 查看某一个作业
     */
    @GetMapping("/{homeworkId}")
    public ResponseEntity<R<HomeworkDetail>> getHomework(@PathVariable Long homeworkId) {
        HomeworkDetail homework = homeworkService.getHomeworkById(homeworkId);
        return ResponseEntity.ok(R.success(homework));
    }

    /**
     * 教师删除作业
     * @param homeworkId 作业ID
     * @return 操作结果
     */
    @DeleteMapping("/{homeworkId}")
    public ResponseEntity<R<Void>> deleteHomework(@PathVariable Long homeworkId) {
        // NOTE: 教师和管理员可以删除作业
        if (!(userInfoUtils.currentUserInClassIsTeacher() ||
                AuthenticationUserUtil.currentUserIsAdmin()))
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);

        HomeworkDetail homework = homeworkService.getHomeworkById(homeworkId);
        // 只可以删除自己发布的作业
        if (!Objects.equals(homework.getTeacherId(), AuthenticationUserUtil.getCurrentUserId()))
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);

        homeworkService.deleteHomework(homeworkId);
        return ResponseEntity.ok(R.success(null));
    }

    // --- Student Endpoints ---

    /**
     * 学生查看所有作业列表（即学生所在班级的所有作业列表）
     */
    @GetMapping("/student/all")
    public ResponseEntity<R<List<HomeworkDetail>>> getAllHomeworks() {
        List<HomeworkDetail> homeworks = homeworkService.getAllHomeworksForStudent(AuthenticationUserUtil.getCurrentUserName());
        return ResponseEntity.ok(R.success(homeworks));
    }

    /**
     * 查看某个班级的所有作业
     */
    @GetMapping("/class/{classId}")
    public ResponseEntity<R<List<HomeworkDetail>>> getHomeworksByClassId(@PathVariable Long classId) {
        List<HomeworkDetail> result = homeworkService.getAllHomeworksInClass(classId);
        return ResponseEntity.ok(R.success(result));
    }

}
