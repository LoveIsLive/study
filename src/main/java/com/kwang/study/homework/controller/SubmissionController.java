package com.kwang.study.homework.controller;


import com.kwang.study.common.R;
import com.kwang.study.homework.dto.request.SubmissionCreateDTO;
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
     * 表单字段：homeworkId, studentId, content
     * 文件字段：files
     */
    @PostMapping("/submit")
    public ResponseEntity<R<HomeworkSubmission>> submitHomework(@Valid @RequestPart("dto") SubmissionCreateDTO dto,
                                                             @RequestPart(value = "files", required = false) List<MultipartFile> files) throws IOException {
        // studentId 应从认证信息获取
        HomeworkSubmission submission = homeworkService.createSubmission(dto, files);
        return ResponseEntity.ok(R.success(submission));
    }

    /**
     * 学生查看自己的提交记录列表
     */
    @GetMapping("/student/{studentId}")
    public ResponseEntity<R<List<HomeworkSubmission>>> getStudentSubmissions(@PathVariable Long studentId) {
        // studentId 应从认证信息获取，并进行权限校验
        List<HomeworkSubmission> submissions = homeworkService.getSubmissionsByStudent(studentId);
        return ResponseEntity.ok(R.success(submissions));
    }


}
