package com.kwang.study.mathvision.controller;

import com.kwang.study.common.R;
import com.kwang.study.constant.ApiPrefixConstant;
import com.kwang.study.mathvision.dto.MathVisionTaskCreateRequestDTO;
import com.kwang.study.mathvision.dto.MathVisionTaskCreateResponseDTO;
import com.kwang.study.mathvision.dto.MathVisionTaskDetailVO;
import com.kwang.study.mathvision.dto.MathVisionTaskItemVO;
import com.kwang.study.mathvision.dto.MathVisionTaskRuntimeSettingsRequestDTO;
import com.kwang.study.mathvision.dto.MathVisionTaskTitleUpdateRequestDTO;
import com.kwang.study.mathvision.dto.MathVisionVersionDetailVO;
import com.kwang.study.mathvision.dto.MathVisionVersionItemVO;
import com.kwang.study.mathvision.dto.PageResultVO;
import com.kwang.study.mathvision.dto.StageConfirmRequestDTO;
import com.kwang.study.mathvision.dto.StageQualityReviewRequestDTO;
import com.kwang.study.mathvision.dto.StageAutoEditRequestDTO;
import com.kwang.study.mathvision.dto.StageContentSaveRequestDTO;
import com.kwang.study.mathvision.dto.StageDataVO;
import com.kwang.study.mathvision.dto.StageOperationResultVO;
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

    /** 分页查询当前用户回收站中的任务。 */
    @GetMapping("/recycle-bin")
    public ResponseEntity<R<PageResultVO<MathVisionTaskItemVO>>> listDeletedTasks(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return ResponseEntity.ok(R.success(taskService.listDeletedTasks(keyword, page, size)));
    }

    /** 任务详情。 */
    @GetMapping("/{taskId}")
    public ResponseEntity<R<MathVisionTaskDetailVO>> getTask(@PathVariable Long taskId) {
        return ResponseEntity.ok(R.success(taskService.getTaskDetail(taskId)));
    }

    @PutMapping("/{taskId}/runtime-settings")
    public ResponseEntity<R<MathVisionTaskDetailVO>> updateRuntimeSettings(
            @PathVariable Long taskId,
            @Valid @RequestBody MathVisionTaskRuntimeSettingsRequestDTO request) {
        return ResponseEntity.ok(R.success(taskService.updateRuntimeSettings(taskId, request)));
    }

    @PutMapping("/{taskId}/title")
    public ResponseEntity<R<MathVisionTaskDetailVO>> updateTitle(
            @PathVariable Long taskId,
            @Valid @RequestBody MathVisionTaskTitleUpdateRequestDTO request) {
        return ResponseEntity.ok(R.success(taskService.updateTaskTitle(taskId, request)));
    }

    /** 获取任务版本列表。 */
    @GetMapping("/{taskId}/versions")
    public ResponseEntity<R<List<MathVisionVersionItemVO>>> listVersions(@PathVariable Long taskId) {
        return ResponseEntity.ok(R.success(taskService.listTaskVersions(taskId)));
    }

    /** 获取指定版本的完整阶段快照。 */
    @GetMapping("/{taskId}/artifacts/{version}")
    public ResponseEntity<R<MathVisionVersionDetailVO>> getVersionDetail(
            @PathVariable Long taskId,
            @PathVariable Integer version) {
        return ResponseEntity.ok(R.success(taskService.getTaskVersionDetail(taskId, version)));
    }

    /** 激活指定任务版本。 */
    @PostMapping("/{taskId}/versions/{version}/activate")
    public ResponseEntity<R<MathVisionTaskDetailVO>> activateVersion(
            @PathVariable Long taskId,
            @PathVariable Integer version) {
        return ResponseEntity.ok(R.success(taskService.activateTaskVersion(taskId, version)));
    }

    @GetMapping("/{taskId}/stages/current")
    public ResponseEntity<R<StageDataVO>> getCurrentStage(@PathVariable Long taskId) {
        return ResponseEntity.ok(R.success(taskService.getStageData(taskId, null)));
    }

    @GetMapping("/{taskId}/stages/{stage}")
    public ResponseEntity<R<StageDataVO>> getStage(@PathVariable Long taskId,
                                                   @PathVariable String stage) {
        return ResponseEntity.ok(R.success(taskService.getStageData(taskId, stage)));
    }

    @PutMapping("/{taskId}/stages/{stage}/content")
    public ResponseEntity<R<StageOperationResultVO>> saveStageContent(
            @PathVariable Long taskId,
            @PathVariable String stage,
            @Valid @RequestBody StageContentSaveRequestDTO request) {
        return ResponseEntity.ok(R.success(taskService.saveStageContent(taskId, stage, request)));
    }

    @PostMapping("/{taskId}/stages/{stage}/auto-edit")
    public ResponseEntity<R<StageOperationResultVO>> autoEditStage(
            @PathVariable Long taskId,
            @PathVariable String stage,
            @Valid @RequestBody StageAutoEditRequestDTO request) {
        return ResponseEntity.ok(R.success(taskService.autoEditStage(taskId, stage, request)));
    }

    @PostMapping("/{taskId}/stages/{stage}/confirm")
    public ResponseEntity<R<MathVisionTaskDetailVO>> confirmStage(
            @PathVariable Long taskId,
            @PathVariable String stage,
            @Valid @RequestBody StageConfirmRequestDTO request) {
        return ResponseEntity.ok(R.success(taskService.confirmStage(taskId, stage, request)));
    }

    @PostMapping("/{taskId}/stages/{stage}/quality-review")
    public ResponseEntity<R<MathVisionTaskDetailVO>> requestQualityReview(
            @PathVariable Long taskId,
            @PathVariable String stage,
            @Valid @RequestBody StageQualityReviewRequestDTO request) {
        return ResponseEntity.ok(R.success(
                taskService.requestStageQualityReview(taskId, stage, request)));
    }

    @PostMapping("/{taskId}/stages/{stage}/retry")
    public ResponseEntity<R<MathVisionTaskDetailVO>> retryStage(
            @PathVariable Long taskId,
            @PathVariable String stage) {
        return ResponseEntity.ok(R.success(taskService.retryTaskStage(taskId, stage)));
    }

    /** 基于当前版本重新生成指定阶段，并使该阶段及全部后续阶段重新执行。 */
    @PostMapping("/{taskId}/stages/{stage}/regenerate")
    public ResponseEntity<R<MathVisionTaskDetailVO>> regenerateStage(
            @PathVariable Long taskId,
            @PathVariable String stage) {
        return ResponseEntity.ok(R.success(taskService.regenerateTaskStage(taskId, stage)));
    }

    /** 启动当前用户的任务; 每次只入队一个用户可见阶段。 */
    @PostMapping("/{taskId}/start")
    public ResponseEntity<R<MathVisionTaskDetailVO>> startTask(@PathVariable Long taskId) {
        return ResponseEntity.ok(R.success(taskService.startTask(taskId)));
    }

    /** 取消当前用户的任务; running 状态会先写入 cancel_requested, 由阶段执行边界收敛。 */
    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<R<MathVisionTaskDetailVO>> cancelTask(@PathVariable Long taskId) {
        return ResponseEntity.ok(R.success(taskService.cancelTask(taskId)));
    }

    /** 将任务移入回收站。 */
    @DeleteMapping("/{taskId}")
    public ResponseEntity<R<Void>> deleteTask(@PathVariable Long taskId) {
        taskService.deleteTask(taskId);
        return ResponseEntity.ok(R.success(null));
    }

    /** 从回收站恢复任务。 */
    @PostMapping("/{taskId}/restore")
    public ResponseEntity<R<MathVisionTaskDetailVO>> restoreTask(@PathVariable Long taskId) {
        return ResponseEntity.ok(R.success(taskService.restoreTask(taskId)));
    }

    /** 永久删除回收站任务。 */
    @DeleteMapping("/{taskId}/permanent")
    public ResponseEntity<R<Void>> permanentlyDeleteTask(@PathVariable Long taskId) {
        taskService.permanentlyDeleteTask(taskId);
        return ResponseEntity.ok(R.success(null));
    }
}
