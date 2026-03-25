package com.kwang.study.organization.service;

import com.kwang.study.auth.constant.AuthConstant;
import com.kwang.study.auth.mapper.UserMapper;
import com.kwang.study.auth.pojo.User;
import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.organization.dto.request.ClassMemberAddDTO;
import com.kwang.study.organization.enums.ClassesRoleEnum;
import com.kwang.study.organization.enums.SchoolRoleEnum;
import com.kwang.study.organization.mapper.ClassMemberMapper;
import com.kwang.study.organization.mapper.ClassesMapper;
import com.kwang.study.organization.mapper.SchoolMemberMapper;
import com.kwang.study.organization.pojo.ClassMember;
import com.kwang.study.organization.pojo.Classes;
import com.kwang.study.organization.pojo.SchoolMember;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ClassMemberService {

    private final ClassMemberMapper classMemberMapper;
    private final ClassesMapper classesMapper;
    private final SchoolMemberMapper schoolMemberMapper;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    /**
     * 向班级中批量添加成员
     * @param classId 班级ID
     * @param dto 包含用户ID列表和角色的数据传输对象
     */
    @Transactional
    public void addMembers(Long classId, ClassMemberAddDTO dto) {
        // 1. 获取班级信息
        Classes classes = classesMapper.findById(classId);
        Assert.notNull(classes, "班级不存在，ID: " + classId);

        // 2. 权限校验
        checkWritePermission(classes);

        // 3. 准备用户名前缀 前缀策略: S{schoolId}_C{classId}_{username}
        String prefix = "S" + classes.getSchoolId() + "_C" + classId + "_";

        List<ClassMember> newMembers = new ArrayList<>();

        for (String rawUserName : dto.getUserNames()) {
            String realUsername = prefix + rawUserName;

            // 4. 查找或创建用户
            User user = userMapper.findByUsername(realUsername);
            if (user == null) {
                user = new User();
                user.setUsername(realUsername);
                user.setPassword(passwordEncoder.encode(AuthConstant.DEFAULT_PASSWORD));
                user.setEnabled(true);
                userMapper.insertUser(user);
                // 此时不再操作 user_roles 表，因为角色由 class_members 表决定
            }

            // 5. 检查是否已经是该班级成员
            ClassMember existingMember = classMemberMapper.findByClassIdAndUserId(classId, user.getId());
            if (existingMember != null) {
                log.info("用户 {} 已经是班级成员，跳过", realUsername);
                continue;
            }

            ClassMember member = new ClassMember();
            member.setClassId(classId);
            member.setUserId(user.getId());
            member.setRole(dto.getRole());
            newMembers.add(member);
        }

        if (!newMembers.isEmpty()) {
            classMemberMapper.batchInsert(newMembers);
            log.info("成功向班级 {} 添加了 {} 名新成员", classId, newMembers.size());
        }
    }

    /**
     * 从班级中批量删除成员
     * @param classId 班级ID
     * @param userIds 要删除的用户ID列表
     */
    @Transactional
    public void removeMembers(Long classId, List<Long> userIds) {
        Assert.notEmpty(userIds, "用户ID列表不能为空");

        // 1. 获取班级信息
        Classes classes = classesMapper.findById(classId);
        Assert.notNull(classes, "班级不存在，ID: " + classId);

        // 2. 权限校验
        checkWritePermission(classes);

        // 3. 执行删除
        int affectedRows = classMemberMapper.deleteByClassIdAndUserIds(classId, userIds);

        // 注意：这里仅移除班级关系，保留用户账号。
        // 如果业务要求学生退班即销号，可以在这里调用 userMapper.deleteUser(userId)
        log.info("从班级 {} 尝试删除 {} 名成员，实际删除 {} 名", classId, userIds.size(), affectedRows);
    }

    /**
     * 获取班级的所有学生列表
     */
    public List<ClassMember> getStudentsInClass(Long classId) {
        checkReadPermission(classId);
        return classMemberMapper.findUsersByClassIdAndRole(classId, ClassesRoleEnum.STUDENT.getRole());
    }

    /**
     * 获取班级的所有教师列表
     */
    public List<ClassMember> getTeachersInClass(Long classId) {
        checkReadPermission(classId);
        return classMemberMapper.findUsersByClassIdAndRole(classId, ClassesRoleEnum.TEACHER.getRole());
    }

    /**
     * 获取班级的所有成员列表（包含教师和学生）
     */
    public List<ClassMember> getAllMembersInClass(Long classId) {
        checkReadPermission(classId);
        return classMemberMapper.findMembersByClassId(classId);
    }

    /**
     * 统计一个班级内有多少成员
     */
    public Long countMemberByClassId(Long classId) {
        // 统计接口权限可以稍微放宽，或者与 Read 保持一致
        checkReadPermission(classId);
        return classMemberMapper.countMemberByClassId(classId);
    }

    // ============================ 权限校验辅助方法 ============================

    /**
     * 校验写入权限 (添加/删除成员)
     * 允许：Admin, 该校校长, 该班教师
     */
    private void checkWritePermission(Classes classes) {
        // 1. Admin 放行
        if (AuthenticationUserUtil.currentUserIsAdmin()) {
            return;
        }

        Long currentUserId = AuthenticationUserUtil.getCurrentUserId();
        Assert.notNull(currentUserId, "无法获取用户信息");

        // 2. 检查是否是该校校长
        SchoolMember schoolMember = schoolMemberMapper.findByUserId(currentUserId);
        if (schoolMember != null &&
                SchoolRoleEnum.PRINCIPAL.getRole().equals(schoolMember.getRole()) &&
                Objects.equals(schoolMember.getSchoolId(), classes.getSchoolId())) {
            return;
        }

        // 3. 检查是否是该班级的教师
        ClassMember classMember = classMemberMapper.findByClassIdAndUserId(classes.getId(), currentUserId);
        if (classMember != null && ClassesRoleEnum.TEACHER.getRole().equals(classMember.getRole())) {
            return;
        }

        throw new IllegalArgumentException("无权限操作该班级成员");
    }

    /**
     * 校验读取权限 (查询成员列表)
     * 允许：Admin, 该校校长, 该班成员(老师/学生)
     */
    private void checkReadPermission(Long classId) {
        // 1. Admin 放行
        if (AuthenticationUserUtil.currentUserIsAdmin()) {
            return;
        }

        Classes classes = classesMapper.findById(classId);
        Assert.notNull(classes, "班级不存在");

        Long currentUserId = AuthenticationUserUtil.getCurrentUserId();
        Assert.notNull(currentUserId, "无法获取用户信息");

        // 2. 检查是否是该校校长
        SchoolMember schoolMember = schoolMemberMapper.findByUserId(currentUserId);
        if (schoolMember != null &&
                SchoolRoleEnum.PRINCIPAL.getRole().equals(schoolMember.getRole()) &&
                Objects.equals(schoolMember.getSchoolId(), classes.getSchoolId())) {
            return;
        }

        // 3. 检查是否是该班级的成员 (老师或学生都可以看通讯录)
        ClassMember classMember = classMemberMapper.findByClassIdAndUserId(classId, currentUserId);
        if (classMember != null) {
            return;
        }

        throw new IllegalArgumentException("无权限查看该班级成员");
    }
}