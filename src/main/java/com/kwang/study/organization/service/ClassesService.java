package com.kwang.study.organization.service;

import com.kwang.study.organization.dto.request.ClassCreateDTO;
import com.kwang.study.organization.dto.result.ClassDetailDTO;
import com.kwang.study.organization.mapper.ClassesMapper;
import com.kwang.study.organization.pojo.Classes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.List;

/**
 * 班级管理服务层
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ClassesService {

    private final ClassesMapper classesMapper;

    /**
     * 创建一个新班级
     * @param dto 包含班级名称的DTO
     * @return 创建后的班级对象
     */
    @Transactional
    public Classes createClass(ClassCreateDTO dto) {
        Classes existing = classesMapper.findByName(dto.getName());
        Assert.isNull(existing, "班级名称 '" + dto.getName() + "' 已存在");

        Classes newClass = new Classes();
        newClass.setName(dto.getName());

        classesMapper.insert(newClass);
        log.info("创建班级成功: id={}, name={}", newClass.getId(), newClass.getName());
        return newClass;
    }

    /**
     * 更新班级信息（目前仅支持名称）
     * @param classId 班级ID
     * @param dto 包含新name的DTO
     * @return 更新后的班级对象
     */
    @Transactional
    public Classes updateClass(Long classId, ClassCreateDTO dto) {
        Classes existingClass = classesMapper.findById(classId);
        Assert.notNull(existingClass, "班级不存在，ID: " + classId);

        // 如果名称有变化，检查新名称是否被占用
        if (!existingClass.getName().equals(dto.getName())) {
            Classes byNewName = classesMapper.findByName(dto.getName());
            Assert.isTrue(byNewName == null || byNewName.getId().equals(classId),
                    "班级名称 '" + dto.getName() + "' 已存在");
        }

        existingClass.setName(dto.getName());
        classesMapper.update(existingClass);
        return existingClass;
    }

    /**
     * 根据ID删除一个班级。
     * 注意：根据要求，此操作不会删除 class_members 表中的关联成员，
     * TODO: 删除操作很复杂，暂时未处理
     * @param classId 班级ID
     */
    @Transactional
    public void deleteClass(Long classId) {
        Classes existingClass = classesMapper.findById(classId);
        Assert.notNull(existingClass, "班级不存在，无法删除，ID: " + classId);

        int affectedRows = classesMapper.deleteById(classId);
        if (affectedRows > 0) {
            log.warn("已删除班级 {}。注意：该班级的成员关系记录未被清理。", classId);
        }
    }

    // --- 基本信息查询 ---

    public List<Classes> getAllClasses() {
        return classesMapper.findAll();
    }

    public Classes getClassById(Long id) {
        return classesMapper.findById(id);
    }

    public Classes getClassByName(String name) {
        return classesMapper.findByName(name);
    }

    public List<Classes> searchClassByName(String key) {
        return classesMapper.matchByName(key);
    }

    // --- 详细信息查询 (包含成员数) ---

    public List<ClassDetailDTO> getAllClassesDetail() {
        return classesMapper.findAllDetail();
    }

    public ClassDetailDTO getClassDetailById(Long id) {
        return classesMapper.findClassDetailById(id);
    }

    public ClassDetailDTO getClassDetailByName(String name) {
        return classesMapper.findClassDetailByName(name);
    }

    public List<ClassDetailDTO> searchClassDetailByName(String key) {
        return classesMapper.matchClassDetailByName(key);
    }
}