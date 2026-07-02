package com.kwang.study.mathvision.controller;

import com.kwang.study.common.R;
import com.kwang.study.constant.ApiPrefixConstant;
import com.kwang.study.mathvision.dto.MathVisionTaskCreateRequestDTO;
import com.kwang.study.mathvision.dto.MathVisionTaskCreateResponseDTO;
import com.kwang.study.mathvision.dto.MathVisionTaskDetailVO;
import com.kwang.study.mathvision.dto.MathVisionTaskItemVO;
import com.kwang.study.mathvision.dto.PageResultVO;
import com.kwang.study.mathvision.service.MathVisionTaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.util.List;

/**
 * MathVision 任务创建 / 列表 / 详情接口。
 * userId 一律取登录态, 前端不传。
 */
@RestController
@RequestMapping(ApiPrefixConstant.MATHVISION_BASE_PREFIX + "/tasks")
@Validated
public class MathVisionTaskController {

    private final MathVisionTaskService taskService;

    public MathVisionTaskController(MathVisionTaskService taskService) {
        this.taskService = taskService;
    }

    /** 创建任务; request 为 JSON 部分, files 为随请求上传的小文件。 */
    @PostMapping("/create")
    public ResponseEntity<R<MathVisionTaskCreateResponseDTO>> createTask(
            @Valid @RequestPart("request") MathVisionTaskCreateRequestDTO request,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        return ResponseEntity.ok(R.success(taskService.createTask(request, files)));
    }

    /** 分页查询当前用户任务列表。 */
    @GetMapping
    public ResponseEntity<R<PageResultVO<MathVisionTaskItemVO>>> listTasks(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "outputTarget", required = false) String outputTarget,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return ResponseEntity.ok(R.success(taskService.listTasks(keyword, status, outputTarget, page, size)));
    }

    /** 任务详情。 */
    @GetMapping("/{taskId}")
    public ResponseEntity<R<MathVisionTaskDetailVO>> getTask(@PathVariable Long taskId) {
        return ResponseEntity.ok(R.success(taskService.getTaskDetail(taskId)));
    }
}
