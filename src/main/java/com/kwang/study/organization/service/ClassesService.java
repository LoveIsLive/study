package com.kwang.study.organization.service;

import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.enums.FileStorageModuleNameEnum;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.organization.dto.request.ClassCreateDTO;
import com.kwang.study.organization.dto.result.ClassDetailDTO;
import com.kwang.study.organization.enums.SchoolRoleEnum;
import com.kwang.study.organization.mapper.ClassesMapper;
import com.kwang.study.organization.mapper.SchoolMapper;
import com.kwang.study.organization.pojo.ClassMember;
import com.kwang.study.organization.pojo.Classes;
import com.kwang.study.organization.pojo.School;
import com.kwang.study.organization.pojo.SchoolMember;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 班级管理服务层
 * 核心原则：所有操作必须校验 schoolId 边界
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ClassesService {

    private final ClassesMapper classesMapper;
    private final SchoolMapper schoolMapper;
    private final FileStorageService fileStorageService;
    private final UserInfoUtils userInfoUtils;

    // =================================================================================
    // =================================== 写操作 =======================================
    // =================================================================================

    /**
     * 创建一个新班级
     * 权限规则：
     * 1. Admin: 必须指定 schoolId
     * 2. Principal: 只能在自己的学校创建
     */
    @Transactional
    public Classes createClass(ClassCreateDTO dto) {
        // 1. 确定目标学校ID
        Long targetSchoolId;
        if (AuthenticationUserUtil.currentUserIsAdmin()) {
            Assert.notNull(dto.getSchoolId(), "管理员创建班级必须指定所属学校ID");
            targetSchoolId = dto.getSchoolId();
        } else {
            // 获取当前用户的归属学校（包含校长校验）
            targetSchoolId = getAndValidateCurrentSchoolId();
            // 如果前端传了ID但与用户归属不一致，报错或直接忽略（此处选择报错以示严格）
            if (dto.getSchoolId() != null && !Objects.equals(dto.getSchoolId(),  targetSchoolId)) {
                throw new IllegalArgumentException("您无权在其他学校创建班级");
            }
        }

        // 2. 校验学校是否存在
        School school = schoolMapper.findById(targetSchoolId);
        Assert.notNull(school, "目标学校不存在，ID: " + targetSchoolId);

        // 3. 校验同校重名
        Classes existing = classesMapper.findByNameAndSchoolId(dto.getName(), targetSchoolId);
        Assert.isNull(existing, "该学校下已存在名为 '" + dto.getName() + "' 的班级");

        // 4. 插入数据库
        Classes newClass = new Classes();
        newClass.setName(dto.getName());
        newClass.setSchoolId(targetSchoolId);

        classesMapper.insert(newClass);

        // 5. 创建文件系统目录 /ware/{schoolId}/{classId}
        initializeClassDirectories(targetSchoolId, newClass.getId());

        log.info("创建班级成功: id={}, name={}, schoolId={}", newClass.getId(), newClass.getName(), targetSchoolId);
        return newClass;
    }

    /**
     * 更新班级信息
     * 权限规则：Admin, 该校校长
     */
    @Transactional
    public Classes updateClass(Long classId, ClassCreateDTO dto) {
        // 1. 获取原班级信息
        Classes existingClass = classesMapper.findById(classId);
        Assert.notNull(existingClass, "班级不存在，ID: " + classId);

        // 2. 权限校验：检查操作者是否有权操作该学校的数据
        validateWriteClassAccess(existingClass.getSchoolId());

        // 3. 重名校验 (同一学校下)
        if (!existingClass.getName().equals(dto.getName())) {
            Classes byNewName = classesMapper.findByNameAndSchoolId(dto.getName(), existingClass.getSchoolId());
            Assert.isTrue(byNewName == null || byNewName.getId().equals(classId),
                    "该学校下班级名称 '" + dto.getName() + "' 已存在");
        }

        existingClass.setName(dto.getName());
        classesMapper.update(existingClass);
        return existingClass;
    }

    /**
     * 删除班级
     * 权限规则：操作者必须有权限访问该班级所属的学校
     */
    @Transactional
    public void deleteClass(Long classId) {
        Classes existingClass = classesMapper.findById(classId);
        Assert.notNull(existingClass, "班级不存在，ID: " + classId);

        // 权限校验
        validateWriteClassAccess(existingClass.getSchoolId());

        int affectedRows = classesMapper.deleteById(classId);
        if (affectedRows > 0) {
            log.warn("已删除班级 {} (School: {})。注意：关联数据需清理。", classId, existingClass.getSchoolId());
            // TODO: 触发异步任务清理 /ware/{schoolId}/{classId} 及成员关系
        }
    }

    // =================================================================================
    // =================================== 读操作 =======================================
    // =================================================================================

    /**
     * 获取班级列表
     * 权限规则：
     * 1. Admin: 必须传入 targetSchoolId
     * 2. 非Admin: 强制只能查看自己所在学校
     */
    public List<Classes> getAllClasses(Long targetSchoolId) {
        validateReadClassAccess(targetSchoolId);

        Long resolvedSchoolId = resolveAndValidateSchoolId(targetSchoolId);
        return classesMapper.findAllBySchoolId(resolvedSchoolId);
    }

    /**
     * 根据ID获取班级
     * 权限规则：Admin，或者在该校
     */
    public Classes getClassById(Long id) {
        Classes classes = classesMapper.findById(id);
        Assert.notNull(classes, "班级不存在");
        
        validateReadClassAccess(classes.getSchoolId());

        return classes;
    }

    /**
     * 批量根据ID查询班级
     * 权限规则：
     * 1. Admin: 查询所有传入ID对应的班级
     * 2. 校长: 查询传入ID中，且属于自己学校的班级
     */
    public List<Classes> getBatchClassByIds(List<Long> ids) {
        Assert.isTrue(AuthenticationUserUtil.currentUserIsAdmin() || userInfoUtils.currentUserInSchoolIsPrincipal(),
                "没有操作权限");
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        Long filterSchoolId = null;
        if (!AuthenticationUserUtil.currentUserIsAdmin()) {
            // 非管理员，必须强制加上 schoolId 过滤
            filterSchoolId = getAndValidateCurrentSchoolId();
        }

        return classesMapper.selectBatchIds(ids, filterSchoolId);
    }

    /**
     * 根据名称搜索 (内部调用或精确查找)
     */
    public Classes getClassByName(String name, Long targetSchoolId) {
        validateReadClassAccess(targetSchoolId);
        
        Long resolvedSchoolId = resolveAndValidateSchoolId(targetSchoolId);
        return classesMapper.findByNameAndSchoolId(name, resolvedSchoolId);
    }

    /**
     * 模糊搜索班级
     */
    public List<Classes> searchClassByName(String key, Long targetSchoolId) {
        validateReadClassAccess(targetSchoolId);

        Long resolvedSchoolId = resolveAndValidateSchoolId(targetSchoolId);
        return classesMapper.matchByNameAndSchoolId(key, resolvedSchoolId);
    }

    // --- 详细信息查询 ---

    public List<ClassDetailDTO> getAllClassesDetail(Long targetSchoolId) {
        Long resolvedSchoolId = resolveAndValidateSchoolId(targetSchoolId);

        validateReadClassAccess(resolvedSchoolId);

        return classesMapper.findAllDetailBySchoolId(resolvedSchoolId);
    }

    public ClassDetailDTO getClassDetailById(Long id) {
        ClassDetailDTO detail = classesMapper.findClassDetailById(id);
        Assert.notNull(detail, "班级不存在");

        // 读取权限校验
        validateReadClassAccess(detail.getSchoolId());
        return detail;
    }

    public ClassDetailDTO getClassDetailByName(String name, Long targetSchoolId) {
        validateReadClassAccess(targetSchoolId);

        Long resolvedSchoolId = resolveAndValidateSchoolId(targetSchoolId);
        return classesMapper.findClassDetailByNameAndSchoolId(name, resolvedSchoolId);
    }

    public List<ClassDetailDTO> searchClassDetailByName(String key, Long targetSchoolId) {
        validateReadClassAccess(targetSchoolId);

        Long resolvedSchoolId = resolveAndValidateSchoolId(targetSchoolId);
        return classesMapper.matchClassDetailByNameAndSchoolId(key, resolvedSchoolId);
    }

    // =================================================================================
    // =============================== 私有辅助方法 =====================================
    // =================================================================================

    /**
     * 获取当前用户的学校ID，如果用户没有学校归属（例如未分配学校），抛出异常。
     * 此方法主要用于 非Admin 用户。
     */
    private Long getAndValidateCurrentSchoolId() {
        // 修正：从激活的身份中取，而不是从 User 的列表里盲目取
        SchoolMember activeSM = userInfoUtils.getCurrentActiveSchoolMember();
        ClassMember activeCM = userInfoUtils.getCurrentActiveClassMember();

        if (activeSM != null) {
            return activeSM.getSchoolId();
        } else if (activeCM != null) {
            return activeCM.getClasses().getSchoolId();
        }

        throw new IllegalArgumentException("无法获取您当前激活的学校上下文");
    }

    /**
     * 校验当前用户是否有权访问指定的 schoolId。
     * - Admin: 允许访问所有。
     * - 当前激活身份是该校校长
     */
    private void validateWriteClassAccess(Long targetSchoolId) {
        if (AuthenticationUserUtil.currentUserIsAdmin() || userInfoUtils.inSchoolPrincipal(targetSchoolId)) {
            return;
        }
        throw new IllegalArgumentException("无权访问");
    }

    /**
     * 校验当前用户是否有权访问指定的 schoolId。
     * - Admin: 允许访问所有。
     * - 在该校
     */
    private void validateReadClassAccess(Long targetSchoolId) {
        if (AuthenticationUserUtil.currentUserIsAdmin() || userInfoUtils.inSchool(targetSchoolId)) {
            return;
        }
        throw new IllegalArgumentException("无权访问");
    }

    /**
     * 解析并校验请求中的 schoolId。
     * - Admin: 必须传入 paramSchoolId。
     * - 非Admin: 忽略 paramSchoolId，强制使用自己的 schoolId。
     */
    private Long resolveAndValidateSchoolId(Long paramSchoolId) {
        if (AuthenticationUserUtil.currentUserIsAdmin()) {
            Assert.notNull(paramSchoolId, "管理员操作必须指定学校ID");
            return paramSchoolId;
        } else {
            return getAndValidateCurrentSchoolId();
        }
    }

    /**
     * 初始化班级文件目录
     */
    private void initializeClassDirectories(Long schoolId, Long classId) {
        List<String> dirs = Stream.of(FileStorageModuleNameEnum.WARE_NAME,
                FileStorageModuleNameEnum.HOMEWORK_NAME)
                .map(m -> m.getModuleName() + "/" + schoolId + "/" + classId)
                .collect(Collectors.toList());

        boolean batchSuccess = true;
        for (String dir : dirs) {
            try {
                fileStorageService.createDirectory(dir);
            } catch (IOException e) {
                log.error("目录创建异常: {}", dir, e);
                batchSuccess = false;
            }
        }
        if (!batchSuccess) {
            // 回滚清理
            for (String dir : dirs) {
                try { fileStorageService.deleteDirObject(dir); } catch (IOException e) { /* ignore */ }
            }
            throw new IllegalStateException("班级存储空间初始化失败");
        }
    }
}