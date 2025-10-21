package com.kwang.study.organization.service;

import com.kwang.study.auth.constant.AuthConstant;
import com.kwang.study.auth.mapper.UserMapper;
import com.kwang.study.auth.pojo.User;
import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.organization.dto.request.ClassMemberAddDTO;
import com.kwang.study.organization.enums.ClassesRoleEnum;
import com.kwang.study.organization.mapper.ClassMemberMapper;
import com.kwang.study.organization.mapper.ClassesMapper;
import com.kwang.study.organization.pojo.ClassMember;
import com.kwang.study.organization.pojo.Classes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ClassMemberService {

    private final ClassMemberMapper classMemberMapper;
    private final ClassesMapper classesMapper;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    /**
     * 向班级中批量添加成员
     * @param classId 班级ID
     * @param dto 包含用户ID列表和角色的数据传输对象
     */
    @Transactional
    public void addMembers(Long classId, ClassMemberAddDTO dto) {
        // 1. 校验班级是否存在
        Classes existingClass = classesMapper.findById(classId);
        Assert.notNull(existingClass, "班级不存在，ID: " + classId);

        // 先创建用户
        ArrayList<User> newUsers = new ArrayList<>(dto.getUserNames().size());
        dto.getUserNames().forEach(userName -> {
            User user = new User();
            user.setUsername(userName);
            user.setPassword(passwordEncoder.encode(AuthConstant.DEFAULT_PASSWORD));
            user.setEnabled(true);
            userMapper.insertUser(user);
            userMapper.insertUserRoleByName(user.getId(), dto.getRole());
            newUsers.add(user);
        });

        List<ClassMember> newMembers = newUsers.stream().map(user -> {
            ClassMember member = new ClassMember();
            member.setClassId(classId);
            member.setUserId(user.getId());
            member.setRole(dto.getRole());
            return member;
        }).collect(Collectors.toList());

        classMemberMapper.batchInsert(newMembers);
        log.info("成功向班级 {} 添加了 {} 名新成员", classId, newMembers.size());
    }

    /**
     * 从班级中批量删除成员
     * @param classId 班级ID
     * @param userIds 要删除的用户ID列表
     */
    @Transactional
    public void removeMembers(Long classId, List<Long> userIds) {
        Assert.notEmpty(userIds, "用户ID列表不能为空");

        int affectedRows = classMemberMapper.deleteByClassIdAndUserIds(classId, userIds);
        // TODO: 删除用户，复杂操作。
        log.info("从班级 {} 尝试删除 {} 名成员，实际删除 {} 名", classId, userIds.size(), affectedRows);
    }

    /**
     * 获取班级的所有学生列表
     * @param classId 班级ID
     * @return 学生成员列表（包含用户信息）
     */
    public List<ClassMember> getStudentsInClass(Long classId) {
        return classMemberMapper.findUsersByClassIdAndRole(classId, ClassesRoleEnum.STUDENT.getRole());
    }

    /**
     * 获取班级的所有教师列表
     * @param classId 班级ID
     * @return 教师成员列表（包含用户信息）
     */
    public List<ClassMember> getTeachersInClass(Long classId) {
        return classMemberMapper.findUsersByClassIdAndRole(classId, ClassesRoleEnum.TEACHER.getRole());
    }

    /**
     * 获取班级的所有成员列表（包含教师和学生）
     * @param classId 班级ID
     * @return 班级所有成员列表（包含用户信息）
     */
    public List<ClassMember> getAllMembersInClass(Long classId) {
        return classMemberMapper.findMembersByClassId(classId);
    }

    /**
     * 统计一个班级内有多少成员
     * @param classId 班级ID
     * @return 成员数量
     */
    public Long countMemberByClassId(Long classId) {
        return classMemberMapper.countMemberByClassId(classId);
    }
}