package com.kwang.study.mathvision.controller;

import com.kwang.study.common.R;
import com.kwang.study.constant.ApiPrefixConstant;
import com.kwang.study.mathvision.dto.MathVisionSquareItemVO;
import com.kwang.study.mathvision.dto.MathVisionSquareLoadResultVO;
import com.kwang.study.mathvision.dto.PageResultVO;
import com.kwang.study.mathvision.service.MathVisionSquareService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPrefixConstant.MATHVISION_BASE_PREFIX + "/square")
public class MathVisionSquareController {

    private final MathVisionSquareService squareService;

    public MathVisionSquareController(MathVisionSquareService squareService) {
        this.squareService = squareService;
    }

    @GetMapping
    public ResponseEntity<R<PageResultVO<MathVisionSquareItemVO>>> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "outputTarget", required = false) String outputTarget,
            @RequestParam(value = "mineOnly", defaultValue = "false") boolean mineOnly,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "24") int size) {
        return ResponseEntity.ok(R.success(
                squareService.listPublished(keyword, outputTarget, mineOnly, page, size)));
    }

    @PostMapping("/tasks/{taskId}")
    public ResponseEntity<R<MathVisionSquareItemVO>> publish(@PathVariable Long taskId) {
        return ResponseEntity.ok(R.success(squareService.publishCurrentVersion(taskId)));
    }

    @PostMapping("/{shareId}/load")
    public ResponseEntity<R<MathVisionSquareLoadResultVO>> load(@PathVariable Long shareId) {
        return ResponseEntity.ok(R.success(squareService.loadIntoWorkbench(shareId)));
    }

    @DeleteMapping("/{shareId}")
    public ResponseEntity<R<Void>> unpublish(@PathVariable Long shareId) {
        squareService.unpublish(shareId);
        return ResponseEntity.ok(R.success(null));
    }
}
