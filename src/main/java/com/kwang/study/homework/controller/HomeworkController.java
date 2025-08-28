package com.kwang.study.homework.controller;

import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.common.R;
import com.kwang.study.homework.dto.request.HomeworkCreateDTO;
import com.kwang.study.homework.pojo.Homework;
import com.kwang.study.homework.service.HomeworkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.io.IOException;
import java.util.List;

import static com.kwang.study.constant.ApiPrefixConstant.HOMEWORK_BASE_PREFIX;

@RestController
@RequestMapping(HOMEWORK_BASE_PREFIX)
@RequiredArgsConstructor
@Validated
public class HomeworkController {

    private final HomeworkService homeworkService;

    // --- Teacher Endpoints ---

    /**
     * 教师发布作业
     * 使用 multipart/form-data 格式提交
     * 表单字段：title, content
     * 文件字段：files
     */
    @PostMapping("/publish")
    public ResponseEntity<R<Homework>> publishHomework(@Valid @RequestPart("dto") HomeworkCreateDTO dto,
                                                    @RequestPart(value = "files", required = false) List<MultipartFile> smallFiles) throws IOException {
        // 获取认证身份
        dto.setTeacherId(AuthenticationUserUtil.getCurrentUserId());
        Homework createdHomework = homeworkService.createHomework(dto, smallFiles);
        return ResponseEntity.ok(R.success(createdHomework));
    }

    /**
     * 教师查看自己发布的作业列表
     */
    @GetMapping("/teacher/all")
    public ResponseEntity<R<List<Homework>>> getTeacherHomeworks() {
        Long teacherId = AuthenticationUserUtil.getCurrentUserId();
        List<Homework> homeworks = homeworkService.getHomeworksByTeacher(teacherId);
        return ResponseEntity.ok(R.success(homeworks));
    }

    /**
     * 教师删除作业
     * @param homeworkId 作业ID
     * @return 操作结果
     */
    @DeleteMapping("/{homeworkId}")
    public ResponseEntity<R<Void>> deleteHomework(@PathVariable Long homeworkId) {
        homeworkService.deleteHomework(homeworkId);
        return ResponseEntity.ok(R.success(null));
    }

    // --- Student Endpoints ---

    /**
     * 学生查看所有作业列表
     */
    @GetMapping("/all")
    public ResponseEntity<R<List<Homework>>> getAllHomeworks() {
        List<Homework> homeworks = homeworkService.getAllHomeworksForStudent();
        return ResponseEntity.ok(R.success(homeworks));
    }
}
