package com.kwang.study.homework.controller;

import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.common.R;
import com.kwang.study.homework.dto.request.HomeworkCreateDTO;
import com.kwang.study.homework.dto.request.HomeworkUpdateDTO;
import com.kwang.study.homework.pojo.HomeworkDetail;
import com.kwang.study.homework.pojo.HomeworkSubmissionDetail;
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
     * 权限：目前仅有教师可以发布作业
     */
    @PostMapping("/publish")
    public ResponseEntity<R<HomeworkDetail>> publishHomework(@Valid @RequestPart("dto") HomeworkCreateDTO dto,
                                                             @RequestPart(value = "files", required = false) List<MultipartFile> smallFiles) throws IOException {
        HomeworkDetail createdHomework = homeworkService.createHomework(dto, smallFiles);
        return ResponseEntity.ok(R.success(createdHomework));
    }

    /**
     * 修改作业
     * 权限：管理员、校长、教师，与作业发布者保持一致
     */
    @PutMapping("/{homeworkId}")
    public ResponseEntity<R<HomeworkDetail>> updateHomework(
            @PathVariable Long homeworkId,
            @Valid @RequestPart("dto") HomeworkUpdateDTO dto,
            @RequestPart(value = "files", required = false) List<MultipartFile> smallFiles) throws IOException {
        HomeworkDetail updatedHomework = homeworkService.updateHomework(homeworkId, dto, smallFiles);
        return ResponseEntity.ok(R.success(updatedHomework, "作业修改成功"));
    }

    /**
     * 教师查看自己发布的所有作业
     * 权限：仅教师本人
     */
    @GetMapping("/teacher/all")
    public ResponseEntity<R<List<HomeworkDetail>>> getTeacherHomeworks() {
        List<HomeworkDetail> homeworks = homeworkService.getHomeworksByTeacher();
        return ResponseEntity.ok(R.success(homeworks));
    }

    /**
     * 查看某一个作业
     * 权限：管理员、校长、教师、学生，与作业发布者保持一致
     */
    @GetMapping("/{homeworkId}")
    public ResponseEntity<R<HomeworkDetail>> getHomework(@PathVariable Long homeworkId) {
        HomeworkDetail homework = homeworkService.getHomeworkById(homeworkId);
        return ResponseEntity.ok(R.success(homework));
    }

    /**
     * 删除作业
     * 权限：管理员、校长、教师，与作业发布者保持一致
     */
    @DeleteMapping("/{homeworkId}")
    public ResponseEntity<R<Void>> deleteHomework(@PathVariable Long homeworkId) {
        homeworkService.deleteHomework(homeworkId);
        return ResponseEntity.ok(R.success(null));
    }

    /**
     * 学生查看所有作业列表（即学生所在班级的所有作业列表）
     * 权限：仅学生本人
     */
    @GetMapping("/student/all")
    public ResponseEntity<R<List<HomeworkDetail>>> getAllHomeworks() {
        List<HomeworkDetail> homeworks = homeworkService.getAllHomeworksForStudent();
        return ResponseEntity.ok(R.success(homeworks));
    }

    /**
     * 查看某个班级的所有作业，本质上是查看某个班级的所有教师发布的作业
     * 权限：管理员、校长、教师
     */
    @GetMapping("/class/{classId}")
    public ResponseEntity<R<List<HomeworkDetail>>> getHomeworksByClassId(@PathVariable Long classId) {
        List<HomeworkDetail> result = homeworkService.getAllHomeworksInClass(classId);
        return ResponseEntity.ok(R.success(result));
    }

    /**
     * 打回作业提交
     * 权限：管理员、校长、教师，与作业提交对应的作业发布者保持一致
     */
    @PostMapping("/returnSubmission/{subId}")
    public ResponseEntity<R<HomeworkSubmissionDetail>> returnSubmission(@PathVariable Long subId) {
        HomeworkSubmissionDetail returnedSubmission = homeworkService.returnSubmission(subId);
        return ResponseEntity.ok(R.success(returnedSubmission));
    }
}
