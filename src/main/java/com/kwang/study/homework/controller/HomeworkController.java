package com.kwang.study.homework.controller;

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
     * 表单字段：teacherId, title, content
     * 文件字段：files
     */
    @PostMapping("/publish")
    public ResponseEntity<Homework> publishHomework(@Valid @RequestPart("dto") HomeworkCreateDTO dto,
                                                    @RequestPart(value = "files", required = false) List<MultipartFile> smallFiles) throws IOException {
        // 在真实项目中，teacherId应该从Spring Security的Authentication对象中获取，而不是由前端传递
        Homework createdHomework = homeworkService.createHomework(dto, smallFiles);
        return ResponseEntity.ok(createdHomework);
    }

    /**
     * 教师查看自己发布的作业列表
     */
    @GetMapping("/teacher/{teacherId}")
    public ResponseEntity<List<Homework>> getTeacherHomeworks(@PathVariable Long teacherId) {
        // 同样，teacherId 应来自认证信息，并进行权限校验
        List<Homework> homeworks = homeworkService.getHomeworksByTeacher(teacherId);
        return ResponseEntity.ok(homeworks);
    }

    /**
     * 教师删除作业
     * @param homeworkId 作业ID
     * @return 操作结果
     */
    @DeleteMapping("/{homeworkId}")
    public ResponseEntity<R<Void>> deleteHomework(@PathVariable Long homeworkId) {
        // 权限校验应该在Service层做，或者通过Spring Security的pre/post annotations
        homeworkService.deleteHomework(homeworkId);
        return ResponseEntity.ok(R.success(null));
    }

    // --- Student Endpoints ---

    /**
     * 学生查看所有作业列表
     */
    @GetMapping("/all")
    public ResponseEntity<List<Homework>> getAllHomeworks() {
        List<Homework> homeworks = homeworkService.getAllHomeworksForStudent();
        return ResponseEntity.ok(homeworks);
    }
}
