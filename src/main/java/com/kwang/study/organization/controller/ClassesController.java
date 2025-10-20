package com.kwang.study.organization.controller;

import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.common.R;
import com.kwang.study.constant.ApiPrefixConstant;
import com.kwang.study.organization.dto.request.ClassCreateDTO;
import com.kwang.study.organization.dto.result.ClassDetailDTO;
import com.kwang.study.organization.pojo.Classes;
import com.kwang.study.organization.service.ClassesService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * 班级管理的API接口
 */
@RestController
@RequestMapping(ApiPrefixConstant.CLASSES_BASE_PREFIX)
@RequiredArgsConstructor
@Validated
public class ClassesController {

    private final ClassesService classesService;


    /**
     * 创建新班级
     */
    @PostMapping("/create")
    public ResponseEntity<R<Classes>> createClass(@Valid @RequestBody ClassCreateDTO dto) {
        if (!AuthenticationUserUtil.currentUserIsAdmin())
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);

        Classes createdClass = classesService.createClass(dto);
        return ResponseEntity.ok(R.success(createdClass, "班级创建成功"));
    }

    /**
     * 更新班级信息
     */
    @PutMapping("/{classId}")
    public ResponseEntity<R<Classes>> updateClass(@PathVariable Long classId, @Valid @RequestBody ClassCreateDTO dto) {
        if (!AuthenticationUserUtil.currentUserIsAdmin())
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);

        Classes updatedClass = classesService.updateClass(classId, dto);
        return ResponseEntity.ok(R.success(updatedClass, "班级更新成功"));
    }

    /**
     * 删除班级
     */
    @DeleteMapping("/{classId}")
    public ResponseEntity<R<Void>> deleteClass(@PathVariable Long classId) {
        if (!AuthenticationUserUtil.currentUserIsAdmin())
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);

        classesService.deleteClass(classId);
        return ResponseEntity.ok(R.success(null, "班级删除成功"));
    }


    // --- 读操作 ---

    /**
     * 获取所有班级列表
     * @param detailed 是否返回详细信息(含成员数), 默认为false
     * @return 班级列表 (基本或详细)
     */
    @GetMapping("/all")
    public ResponseEntity<R<?>> getAllClasses(@RequestParam(value = "detailed", defaultValue = "false") boolean detailed) {
        if (detailed) {
            List<ClassDetailDTO> classesDetails = classesService.getAllClassesDetail();
            return ResponseEntity.ok(R.success(classesDetails));
        }
        List<Classes> classes = classesService.getAllClasses();
        return ResponseEntity.ok(R.success(classes));
    }

    /**
     * 根据ID获取单个班级信息
     * @param classId 班级ID
     * @param detailed 是否返回详细信息(含成员数), 默认为false
     * @return 班级信息 (基本或详细)
     */
    @GetMapping("/{classId}")
    public ResponseEntity<R<?>> getClassById(@PathVariable Long classId,
                                             @RequestParam(value = "detailed", defaultValue = "false") boolean detailed) {
        if (detailed) {
            ClassDetailDTO classDetail = classesService.getClassDetailById(classId);
            return ResponseEntity.ok(R.success(classDetail));
        }
        Classes classes = classesService.getClassById(classId);
        return ResponseEntity.ok(R.success(classes));
    }

    /**
     * 根据名称搜索班级
     * @param name (可选) 按精确名称查找
     * @param key (可选) 按模糊名称匹配
     * @param detailed (可选) 是否返回详细信息(含成员数), 默认为false
     * @return 匹配的班级或班级列表 (基本或详细)
     */
    @GetMapping("/search")
    public ResponseEntity<R<?>> searchClasses(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "key", required = false) String key,
            @RequestParam(value = "detailed", defaultValue = "false") boolean detailed) {

        if (name != null) { // 精确查找
            Object result = detailed ? classesService.getClassDetailByName(name) : classesService.getClassByName(name);
            return ResponseEntity.ok(R.success(result));
        }

        if (key != null) { // 模糊匹配
            Object results = detailed ? classesService.searchClassDetailByName(key) : classesService.searchClassByName(key);
            return ResponseEntity.ok(R.success(results));
        }

        return ResponseEntity.badRequest().body(R.error("请提供 'name' 或 'key' 参数进行搜索"));
    }
}