package com.kwang.study.organization.service;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ClassMemberService {

    private final ClassMemberMapper classMemberMapper;
    private final ClassesMapper classesMapper;

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

        // 2. 过滤掉已经存在的成员，防止重复添加
        List<Long> userIdsToAdd = dto.getUserIds();
        if (CollectionUtils.isEmpty(userIdsToAdd)) {
            return;
        }
        List<ClassMember> existingMembers = classMemberMapper.findByClassIdAndUserIds(classId, userIdsToAdd);
        if (!CollectionUtils.isEmpty(existingMembers)) {
            List<Long> existingUserIds = existingMembers.stream().map(ClassMember::getUserId).collect(Collectors.toList());
            userIdsToAdd.removeAll(existingUserIds);
        }

        // 3. 如果没有需要添加的新成员，直接返回
        if (CollectionUtils.isEmpty(userIdsToAdd)) {
            log.warn("没有新的成员需要添加到班级 {}", classId);
            return;
        }

        // 4. 构建成员对象并批量插入
        List<ClassMember> newMembers = userIdsToAdd.stream().map(userId -> {
            ClassMember member = new ClassMember();
            member.setClassId(classId);
            member.setUserId(userId);
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