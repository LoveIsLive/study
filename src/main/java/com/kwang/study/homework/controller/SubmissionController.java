package com.kwang.study.homework.controller;


import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.common.R;
import com.kwang.study.homework.dto.request.SubmissionCreateDTO;
import com.kwang.study.homework.pojo.Homework;
import com.kwang.study.homework.pojo.HomeworkSubmission;
import com.kwang.study.homework.service.HomeworkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.io.IOException;
import java.util.List;

import static com.kwang.study.constant.ApiPrefixConstant.SUBMISSION_BASE_PREFIX;

@RestController
@RequestMapping(SUBMISSION_BASE_PREFIX)
@Validated
public class SubmissionController {

    @Autowired
    private HomeworkService homeworkService; // 也可以拆分为 SubmissionService

    /**
     * 学生提交作业
     * 使用 multipart/form-data 格式提交
     * 表单字段：homeworkId, content
     * 文件字段：files
     */
    @PostMapping("/submit")
    public ResponseEntity<R<HomeworkSubmission>> submitHomework(@Valid @RequestPart("dto") SubmissionCreateDTO dto,
                                                             @RequestPart(value = "files", required = false) List<MultipartFile> files) throws IOException {
        // 获取认证信息
        dto.setStudentId(AuthenticationUserUtil.getCurrentUserId());
        HomeworkSubmission submission = homeworkService.createSubmission(dto, files);
        return ResponseEntity.ok(R.success(submission));
    }

    /**
     * 教师查看特定作业的所有提交
     * TODO: 后续角色权限控制
     * @param homeworkId 作业ID
     * @return 该作业的所有提交列表
     */
    @GetMapping("/{homeworkId}/submissions")
    public ResponseEntity<R<List<HomeworkSubmission>>> getHomeworkSubmissions(@PathVariable Long homeworkId) {
        List<HomeworkSubmission> submissions = homeworkService.getHomeworkSubmissions(homeworkId);
        return ResponseEntity.ok(R.success(submissions));
    }

    /**
     * 学生查看自己的提交记录列表
     */
    @GetMapping("/student/all")
    public ResponseEntity<R<List<HomeworkSubmission>>> getStudentSubmissions() {
        // 获取认证信息
        Long studentId = AuthenticationUserUtil.getCurrentUserId();
        List<HomeworkSubmission> submissions = homeworkService.getSubmissionsByStudent(studentId);
        return ResponseEntity.ok(R.success(submissions));
    }
}
