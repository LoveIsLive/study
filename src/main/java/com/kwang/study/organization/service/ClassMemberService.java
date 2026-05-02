package com.kwang.study.organization.service;

import cn.hutool.core.util.RandomUtil;
import com.kwang.study.auth.constant.AuthConstant;
import com.kwang.study.auth.mapper.UserMapper;
import com.kwang.study.auth.pojo.User;
import com.kwang.study.auth.service.UserService;
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
    private final UserInfoUtils userInfoUtils;

    /**
     * 向班级中批量添加成员
     * @param classId 班级ID
     * @param dto 包含用户ID列表和角色的数据传输对象
     */
    @Transactional
    public List<String> addMembers(Long classId, ClassMemberAddDTO dto) {
        Classes classes = classesMapper.findById(classId);
        Assert.notNull(classes, "班级不存在");
        checkWritePermission(classes);

        List<String> feedbackMessages = new ArrayList<>();
        List<ClassMember> newMembers = new ArrayList<>();

        for (String rawUserName : dto.getUserNames()) {
            // 1. 检查冲突
            User existingUser = userMapper.findByUsername(rawUserName);
            if (existingUser != null) {
                // 如果该用户已经在该班级，直接跳过
                ClassMember cm = classMemberMapper.findByClassIdAndUserId(classId, existingUser.getId());
                // 目前没有将权限也纳入唯一key范围
                if (cm != null) {
                    feedbackMessages.add("用户 " + rawUserName + " 已在班级中，无需重复添加");
                    continue;
                }
            } else {
                // 3. 创建用户
                User user = new User();
                user.setUsername(rawUserName);
                user.setPassword(passwordEncoder.encode(AuthConstant.DEFAULT_PASSWORD));
                user.setEnabled(true);
                userMapper.insertUser(user);
                existingUser = user;
            }

            // 4. 关联班级
            ClassMember member = new ClassMember();
            member.setClassId(classId);
            member.setUserId(existingUser.getId());
            member.setRole(dto.getRole());
            newMembers.add(member);
            userInfoUtils.deleteUserCache(existingUser.getId());
        }

        if (!newMembers.isEmpty()) {
            classMemberMapper.batchInsert(newMembers);
        }
        return feedbackMessages; // 返回反馈信息
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
        userInfoUtils.deleteUsersCache(userIds);

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

        if (userInfoUtils.inSchoolPrincipal(classes.getSchoolId()) || userInfoUtils.inClassTeacher(classes.getId()))
            return;

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

        if (userInfoUtils.inClassOfSchoolPrincipal(classId) || userInfoUtils.inClass(classId))
            return;

        throw new IllegalArgumentException("无权限查看该班级成员");
    }
}