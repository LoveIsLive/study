package com.kwang.study.organization.controller;

import com.kwang.study.common.R;
import com.kwang.study.constant.ApiPrefixConstant;
import com.kwang.study.organization.dto.request.SchoolCreateDTO;
import com.kwang.study.organization.dto.request.SchoolUpdateDTO;
import com.kwang.study.organization.pojo.School;
import com.kwang.study.organization.service.SchoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * 学校管理接口
 * 异常由全局异常处理器捕获，此处直接调用 Service
 */
@RestController
@RequestMapping(ApiPrefixConstant.SCHOOL_BASE_PREFIX)
@RequiredArgsConstructor
@Validated
public class SchoolController {

    private final SchoolService schoolService;

    /**
     * 创建学校 (仅 Admin)
     */
    @PostMapping("/create")
    public ResponseEntity<R<School>> createSchool(@Valid @RequestBody SchoolCreateDTO dto) {
        return ResponseEntity.ok(R.success(schoolService.createSchool(dto)));
    }

    /**
     * 更新学校信息 (Admin 或 本校校长)
     */
    @PutMapping("/{schoolId}")
    public ResponseEntity<R<School>> updateSchool(@PathVariable Long schoolId, @Valid @RequestBody SchoolUpdateDTO dto) {
        return ResponseEntity.ok(R.success(schoolService.updateSchool(schoolId, dto)));
    }

    /**
     * 删除学校 (仅 Admin)
     */
    @DeleteMapping("/{schoolId}")
    public ResponseEntity<R<Void>> deleteSchool(@PathVariable Long schoolId) {
        schoolService.deleteSchool(schoolId);
        return ResponseEntity.ok(R.success(null));
    }

    /**
     * 获取所有学校列表 (仅 Admin)
     */
    @GetMapping("/all")
    public ResponseEntity<R<List<School>>> getAllSchools() {
        return ResponseEntity.ok(R.success(schoolService.getAllSchools()));
    }

    /**
     * 获取单个学校信息 (Admin 或 本校成员)
     */
    @GetMapping("/{schoolId}")
    public ResponseEntity<R<School>> getSchoolById(@PathVariable Long schoolId) {
        return ResponseEntity.ok(R.success(schoolService.getSchoolById(schoolId)));
    }

    /**
     * 搜索学校 (仅 Admin)
     */
    @GetMapping("/search")
    public ResponseEntity<R<List<School>>> searchSchools(@RequestParam("key") String key) {
        return ResponseEntity.ok(R.success(schoolService.searchSchoolByName(key)));
    }
}