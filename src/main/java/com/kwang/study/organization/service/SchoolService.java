package com.kwang.study.organization.service;

import com.kwang.study.auth.pojo.User;
import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.enums.FileStorageModuleNameEnum;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.organization.dto.request.SchoolCreateDTO;
import com.kwang.study.organization.dto.request.SchoolUpdateDTO;
import com.kwang.study.organization.enums.SchoolRoleEnum;
import com.kwang.study.organization.mapper.SchoolMapper;
import com.kwang.study.organization.pojo.School;
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

/**
 * 学校管理服务层
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SchoolService {

    private final SchoolMapper schoolMapper;
    private final FileStorageService fileStorageService;
    private final UserInfoUtils userInfoUtils;

    /**
     * 创建一个新学校
     * 权限：仅 Admin
     */
    @Transactional
    public School createSchool(SchoolCreateDTO dto) {
        // 1. 权限校验
        Assert.isTrue(AuthenticationUserUtil.currentUserIsAdmin(), "无权限创建学校");

        // 2. 校验重名 (全局唯一)
        School existing = schoolMapper.findByName(dto.getName());
        Assert.isNull(existing, "学校名称 '" + dto.getName() + "' 已存在");

        // 3. 插入数据库
        School newSchool = new School();
        newSchool.setName(dto.getName());
        schoolMapper.insert(newSchool);

        // 4. 创建文件系统根目录
        // 路径规范: /ware/{schoolId}, /homework/{schoolId} 等
        List<String> dirs = Arrays.stream(FileStorageModuleNameEnum.values())
                .map(m -> m.getModuleName() + "/" + newSchool.getId())
                .collect(Collectors.toList());

        boolean batchSuccess = true;
        for (String dir : dirs) {
            try {
                fileStorageService.createDirectory(dir);
            } catch (IOException e) {
                log.error("创建学校目录失败: " + dir, e);
                batchSuccess = false;
            }
        }

        // 如果创建目录失败，回滚
        if (!batchSuccess) {
            // 尝试清理已创建的目录
            for (String dir : dirs) {
                try {
                    fileStorageService.deleteDirObject(dir);
                } catch (IOException e) {
                    // ignore
                }
            }
            throw new IllegalStateException("创建学校失败：存储目录初始化异常");
        }

        log.info("创建学校成功: id={}, name={}", newSchool.getId(), newSchool.getName());
        return newSchool;
    }

    /**
     * 更新学校信息
     * 权限：Admin 或 该校校长
     */
    @Transactional
    public School updateSchool(Long schoolId, SchoolUpdateDTO dto) {
        // 1. 权限校验
        validateOperatePermission(schoolId);

        // 2. 检查是否存在
        School existingSchool = schoolMapper.findById(schoolId);
        Assert.notNull(existingSchool, "学校不存在，ID: " + schoolId);

        // 3. 校验重名
        if (!existingSchool.getName().equals(dto.getName())) {
            School byNewName = schoolMapper.findByName(dto.getName());
            Assert.isTrue(byNewName == null || byNewName.getId().equals(schoolId),
                    "学校名称 '" + dto.getName() + "' 已存在");
        }

        existingSchool.setName(dto.getName());
        schoolMapper.update(existingSchool);
        return existingSchool;
    }

    /**
     * 删除学校
     * 权限：仅 Admin
     * 注意：这是一个危险操作，会清理关联的文件目录
     */
    @Transactional
    public void deleteSchool(Long schoolId) {
        // 1. 权限校验
        Assert.isTrue(AuthenticationUserUtil.currentUserIsAdmin(), "无权限删除学校");

        // 2. 检查是否存在
        School existingSchool = schoolMapper.findById(schoolId);
        Assert.notNull(existingSchool, "学校不存在，无法删除");

        // 3. 删除数据库记录 (级联删除逻辑应由数据库外键或额外的Service调用处理，此处只删主表)
        schoolMapper.deleteById(schoolId);
    }

    /**
     * 获取所有学校
     * 权限：仅 Admin
     */
    public List<School> getAllSchools() {
        // 登录页获取列表可能有专门的 public 接口，此接口用于后台管理，限制 Admin
        Assert.isTrue(AuthenticationUserUtil.currentUserIsAdmin(), "无权限查看所有学校");
        return schoolMapper.findAll();
    }

    /**
     * 获取单个学校信息
     * 权限：Admin 或 该校成员
     */
    public School getSchoolById(Long id) {
        // 权限校验
        validateReadPermission(id);
        School school = schoolMapper.findById(id);
        Assert.notNull(school, "学校不存在");
        return school;
    }

    /**
     * 批量根据ID查询学校
     * 权限规则：
     * Admin: 返回所有匹配的学校
     */
    public List<School> getBatchSchoolByIds(List<Long> ids) {
        Assert.isTrue(AuthenticationUserUtil.currentUserIsAdmin(), "无操作权限");
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        return schoolMapper.selectBatchIds(ids);
    }

    /**
     * 通过学校名字查询学校
     * 权限：Admin 或 该校校长
     */
    public School getSchoolByName(String name) {
        School school = schoolMapper.findByName(name);
        Assert.notNull(school, "学校不存在");
        validateOperatePermission(school.getId());
        return school;
    }

    /**
     * 模糊搜索学校
     * 权限：仅 Admin
     */
    public List<School> searchSchoolByName(String key) {
        Assert.isTrue(AuthenticationUserUtil.currentUserIsAdmin(), "无权限搜索学校");
        return schoolMapper.matchByName(key);
    }

    // =================================================================================
    // =============================== 私有权限校验方法 ==================================
    // =================================================================================

    /**
     * 校验操作权限 (用于修改)
     * Admin: 通过
     * 校长: 必须是本校 ID
     * 其他: 拒绝
     */
    private void validateOperatePermission(Long targetSchoolId) {
        if (AuthenticationUserUtil.currentUserIsAdmin()) {
            return;
        }
        // 检查是否是校长
        User user = userInfoUtils.getCurrentUserInfoWithOrgInfo();
        if (user != null && user.getSchoolMember() != null
                && SchoolRoleEnum.PRINCIPAL.getRole().equals(user.getSchoolMember().getRole())) {

            Assert.isTrue(Objects.equals(targetSchoolId, user.getSchoolMember().getSchoolId()),
                    "您无权操作其他学校");
            return;
        }
        throw new IllegalArgumentException("无权限执行此操作");
    }

    /**
     * 校验读取权限 (用于查询)
     * Admin: 通过
     * 校长/老师/学生: 必须是本校 ID
     */
    private void validateReadPermission(Long targetSchoolId) {
        if (AuthenticationUserUtil.currentUserIsAdmin()) {
            return;
        }
        User user = userInfoUtils.getCurrentUserInfoWithOrgInfo();
        Assert.isTrue(user != null && user.getSchoolMember() != null, "无法获取您的学校信息");
        Assert.isTrue(Objects.equals(targetSchoolId, user.getSchoolMember().getSchoolId()), "无权查看其他学校信息");
    }
}