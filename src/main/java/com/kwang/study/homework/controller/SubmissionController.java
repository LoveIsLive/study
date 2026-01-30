package com.kwang.study.homework.controller;


import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.common.R;
import com.kwang.study.homework.dto.json.SubmissionGradingDTO;
import com.kwang.study.homework.dto.request.HomeworkSubmissionUpdateDTO;
import com.kwang.study.homework.dto.request.HomeworkUpdateDTO;
import com.kwang.study.homework.dto.request.SubmissionCreateDTO;
import com.kwang.study.homework.pojo.Homework;
import com.kwang.study.homework.pojo.HomeworkDetail;
import com.kwang.study.homework.pojo.HomeworkSubmission;
import com.kwang.study.homework.pojo.HomeworkSubmissionDetail;
import com.kwang.study.homework.service.HomeworkService;
import org.springframework.beans.factory.annotation.Autowired;
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

import static com.kwang.study.constant.ApiPrefixConstant.SUBMISSION_BASE_PREFIX;

@RestController
@RequestMapping(SUBMISSION_BASE_PREFIX)
@Validated
public class SubmissionController {

    @Autowired
    private HomeworkService homeworkService; // 也可以拆分为 SubmissionService

    @Autowired
    private UserInfoUtils userInfoUtils;

    /**
     * 学生提交作业
     * 权限：学生，与作业发布者保持一致
     */
    @PostMapping("/submit")
    public ResponseEntity<R<HomeworkSubmissionDetail>> submitHomework(@Valid @RequestPart("dto") SubmissionCreateDTO dto,
                                                                      @RequestPart(value = "files", required = false) List<MultipartFile> files) throws IOException {
        HomeworkSubmissionDetail submission = homeworkService.createSubmission(dto, files);
        return ResponseEntity.ok(R.success(submission));
    }

    /**
     * 修改作业提交
     * 权限：仅学生本人
     */
    @PutMapping("/{homeworkSubmissionId}")
    public ResponseEntity<R<HomeworkSubmissionDetail>> updateHomework(
            @PathVariable Long homeworkSubmissionId,
            @Valid @RequestPart("dto") HomeworkSubmissionUpdateDTO dto,
            @RequestPart(value = "files", required = false) List<MultipartFile> smallFiles) throws IOException {
        HomeworkSubmissionDetail updatedHomework = homeworkService.updateHomeworkSubmission(homeworkSubmissionId, dto, smallFiles);
        return ResponseEntity.ok(R.success(updatedHomework, "作业提交修改成功"));
    }

    /**
     * 查看作业的所有提交记录
     * 权限：管理员、校长、教师，与作业发布者保持一致
     */
    @GetMapping("/{homeworkId}/submissions")
    public ResponseEntity<R<List<HomeworkSubmissionDetail>>> getHomeworkSubmissions(@PathVariable Long homeworkId) {
        List<HomeworkSubmissionDetail> submissions = homeworkService.getHomeworkSubmissions(homeworkId);
        return ResponseEntity.ok(R.success(submissions));
    }

    /**
     * 学生查看自己所有的提交记录
     * 权限：仅学生本人
     */
    @GetMapping("/student/all")
    public ResponseEntity<R<List<HomeworkSubmissionDetail>>> getStudentSubmissions() {
        List<HomeworkSubmissionDetail> submissions = homeworkService.getSubmissionsByStudent();
        return ResponseEntity.ok(R.success(submissions));
    }

    /**
     * 学生查看自己某个作业的提交
     * 权限：仅学生本人
     */
    @GetMapping("/student/{homeworkId}/submission")
    public ResponseEntity<R<HomeworkSubmissionDetail>> getStudentSubmission(@PathVariable Long homeworkId) {
        HomeworkSubmissionDetail submission = homeworkService.getSubmissionByStudent(homeworkId);
        return ResponseEntity.ok(R.success(submission));
    }

    /**
     * 查看某个作业提交
     * 权限：学生本人、相应教师、校长管理员
     */
    @GetMapping("/{submissionId}")
    public ResponseEntity<R<HomeworkSubmissionDetail>> getSubmission(@PathVariable Long submissionId) {
        HomeworkSubmissionDetail submission = homeworkService.getSubmissionById(submissionId);
        return ResponseEntity.ok(R.success(submission));
    }

    /**
     * 教师批改作业
     * 权限：管理员、校长、教师（与作业发布者一致）
     */
    @PostMapping("/grade")
    public ResponseEntity<R<HomeworkSubmissionDetail>> gradeSubmission(@RequestBody SubmissionGradingDTO dto) {
        HomeworkSubmissionDetail gradedSubmission = homeworkService.gradeSubmission(dto);
        return ResponseEntity.ok(R.success(gradedSubmission));
    }
}
