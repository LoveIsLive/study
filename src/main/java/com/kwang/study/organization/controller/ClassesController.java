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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * 班级管理的API接口
 * 控制层只负责参数传递和基础的角色判断（粗粒度），
 * 具体的学校/班级数据权限校验全部下沉到 Service 层。
 */
@RestController
@RequestMapping(ApiPrefixConstant.CLASSES_BASE_PREFIX)
@RequiredArgsConstructor
@Validated
public class ClassesController {

    private final ClassesService classesService;

    // =============================== 写操作 ======================================

    /**
     * 创建新班级
     * @param dto 如果是Admin，dto.schoolId 必填
     */
    @PostMapping("/create")
    public ResponseEntity<R<Classes>> createClass(@Valid @RequestBody ClassCreateDTO dto) {
        Classes createdClass = classesService.createClass(dto);
        return ResponseEntity.ok(R.success(createdClass, "班级创建成功"));
    }

    /**
     * 更新班级信息
     */
    @PutMapping("/{classId}")
    public ResponseEntity<R<Classes>> updateClass(@PathVariable Long classId, @Valid @RequestBody ClassCreateDTO dto) {
        Classes updatedClass = classesService.updateClass(classId, dto);
        return ResponseEntity.ok(R.success(updatedClass, "班级更新成功"));
    }

    /**
     * 删除班级
     */
    @DeleteMapping("/{classId}")
    public ResponseEntity<R<Void>> deleteClass(@PathVariable Long classId) {
        classesService.deleteClass(classId);
        return ResponseEntity.ok(R.success(null, "班级删除成功"));
    }


    // =============================== 读操作 ======================================

    /**
     * 获取所有班级列表
     * @param detailed 是否返回详细信息(含成员数)
     * @param schoolId (Admin必填，非Admin忽略) 学校ID
     */
    @GetMapping("/all")
    public ResponseEntity<R<?>> getAllClasses(
            @RequestParam(value = "detailed", defaultValue = "false") boolean detailed,
            @RequestParam(value = "schoolId", required = false) Long schoolId) {

        // Service 层会根据当前角色处理 schoolId：
        // Admin -> 使用传入的 schoolId (若空则抛异常)
        // 校长/老师/学生 -> 忽略传入值，使用自己的 schoolId
        if (detailed) {
            List<ClassDetailDTO> classesDetails = classesService.getAllClassesDetail(schoolId);
            return ResponseEntity.ok(R.success(classesDetails));
        } else {
            List<Classes> classes = classesService.getAllClasses(schoolId);
            return ResponseEntity.ok(R.success(classes));
        }
    }

    /**
     * 根据ID获取单个班级信息
     * Service 层会校验该 ID 对应的班级是否属于当前用户所在的学校 (Admin除外)
     */
    @GetMapping("/{classId}")
    public ResponseEntity<R<?>> getClassById(@PathVariable Long classId,
                                             @RequestParam(value = "detailed", defaultValue = "false") boolean detailed) {
        if (detailed) {
            ClassDetailDTO classDetail = classesService.getClassDetailById(classId);
            return ResponseEntity.ok(R.success(classDetail));
        } else {
            Classes classes = classesService.getClassById(classId);
            return ResponseEntity.ok(R.success(classes));
        }
    }

    /**
     * 根据名称搜索班级
     */
    @GetMapping("/search")
    public ResponseEntity<R<?>> searchClasses(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "key", required = false) String key,
            @RequestParam(value = "detailed", defaultValue = "false") boolean detailed,
            @RequestParam(value = "schoolId", required = false) Long schoolId) {

        if (name != null) { // 精确查找
            Object result = detailed
                    ? classesService.getClassDetailByName(name, schoolId)
                    : classesService.getClassByName(name, schoolId);
            return ResponseEntity.ok(R.success(result));
        }

        if (key != null) { // 模糊匹配
            Object results = detailed
                    ? classesService.searchClassDetailByName(key, schoolId)
                    : classesService.searchClassByName(key, schoolId);
            return ResponseEntity.ok(R.success(results));
        }

        return ResponseEntity.badRequest().body(R.error("请提供 'name' 或 'key' 参数进行搜索"));
    }
}