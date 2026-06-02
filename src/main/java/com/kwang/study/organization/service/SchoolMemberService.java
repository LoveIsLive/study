package com.kwang.study.organization.service;

import com.kwang.study.auth.constant.AuthConstant;
import com.kwang.study.auth.mapper.UserMapper;
import com.kwang.study.auth.pojo.User;
import com.kwang.study.auth.service.UserService;
import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.organization.dto.request.SchoolMemberAddDTO;
import com.kwang.study.organization.mapper.SchoolMapper;
import com.kwang.study.organization.mapper.SchoolMemberMapper;
import com.kwang.study.organization.pojo.School;
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
public class SchoolMemberService {

    private final SchoolMemberMapper schoolMemberMapper;
    private final SchoolMapper schoolMapper;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserInfoUtils userInfoUtils;

    /**
     * 批量添加学校成员（通常是校长）
     * 只有 Admin 可以操作
     */
    @Transactional
    public List<String> addMembers(Long schoolId, SchoolMemberAddDTO dto) {
        Assert.isTrue(AuthenticationUserUtil.currentUserIsAdmin(), "无权限添加学校成员");
        School school = schoolMapper.findById(schoolId);
        Assert.notNull(school, "学校不存在");

        List<String> feedback = new ArrayList<>();
        List<SchoolMember> membersToInsert = new ArrayList<>();

        for (String rawUsername : dto.getUserNames()) {
            // 1. 检查冲突并生成新名
            User existUser = userMapper.findByUsername(rawUsername);
            if (existUser != null) {
                SchoolMember member = schoolMemberMapper.findBySchoolAndUserId(schoolId, existUser.getId());
                // 目前没有将权限也纳入唯一key范围
                if (member != null) {
                    feedback.add("用户 " + rawUsername + " 已在学校中，无需重复添加");
                }
            } else {
                // 2. 创建用户
                User newUser = new User();
                newUser.setUsername(rawUsername);
                newUser.setPassword(passwordEncoder.encode(AuthConstant.DEFAULT_PASSWORD));
                newUser.setEnabled(true);
                newUser.setPasswordExpired(true);
                userMapper.insertUser(newUser);
                existUser = newUser;
            }

            // 3. 建立关联
            SchoolMember member = new SchoolMember();
            member.setSchoolId(schoolId);
            member.setUserId(existUser.getId());
            member.setRole(dto.getRole());
            membersToInsert.add(member);
            userInfoUtils.deleteUserCache(existUser.getId());
        }

        if (!membersToInsert.isEmpty()) {
            schoolMemberMapper.batchInsert(membersToInsert);
            log.info("成功为学校 {} 添加 {} 名成员", schoolId, membersToInsert.size());
        }
        return feedback;
    }

    /**
     * 批量移除学校成员
     * 只有 Admin 可以操作
     */
    @Transactional
    public void removeMembers(Long schoolId, List<Long> userIds) {
        // 1. 权限校验
        Assert.isTrue(AuthenticationUserUtil.currentUserIsAdmin(), "无权限移除学校成员");
        Assert.notEmpty(userIds, "用户ID列表不能为空");

        // 2. 删除成员关联
        int rows = schoolMemberMapper.deleteBySchoolIdAndUserIds(schoolId, userIds);
        userInfoUtils.deleteUsersCache(userIds);

        log.info("从学校 {} 移除了 {} 名成员", schoolId, rows);
    }

    /**
     * 获取指定学校的所有成员列表
     * Admin 或 该校校长可查看
     */
    public List<SchoolMember> getMembers(Long schoolId) {
        // 权限校验
        if (!AuthenticationUserUtil.currentUserIsAdmin()) {
            // 如果不是Admin，检查是否是该校校长
            Assert.isTrue(userInfoUtils.inSchoolPrincipal(schoolId), "无权限");
        }

        return schoolMemberMapper.findMembersBySchoolId(schoolId);
    }
}