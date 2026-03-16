package com.kwang.study.organization.service;

import com.kwang.study.auth.constant.AuthConstant;
import com.kwang.study.auth.mapper.UserMapper;
import com.kwang.study.auth.pojo.User;
import com.kwang.study.auth.utils.AuthenticationUserUtil;
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

    /**
     * 批量添加学校成员（通常是校长）
     * 只有 Admin 可以操作
     */
    @Transactional
    public void addMembers(Long schoolId, SchoolMemberAddDTO dto) {
        // 1. 权限校验
        Assert.isTrue(AuthenticationUserUtil.currentUserIsAdmin(), "无权限添加学校成员");

        // 2. 校验学校是否存在
        School school = schoolMapper.findById(schoolId);
        Assert.notNull(school, "学校不存在");

        List<SchoolMember> membersToInsert = new ArrayList<>();

        for (String rawUsername : dto.getUserNames()) {
            // 3. 构建带前缀的真实用户名: S{schoolId}_CA_{username}
            String realUsername = "S" + schoolId + "_CA_" + rawUsername;

            // 4. 检查用户是否已存在
            User existingUser = userMapper.findByUsername(realUsername);
            if (existingUser != null) {
                // 如果用户已存在，检查是否已经是该校成员
                SchoolMember existingMember = schoolMemberMapper.findByUserId(existingUser.getId());
                if (existingMember != null) {
                    log.warn("用户 {} 已经是成员，跳过", realUsername);
                    continue;
                }
                // 直接建立关联
                SchoolMember member = new SchoolMember();
                member.setSchoolId(schoolId);
                member.setUserId(existingUser.getId());
                member.setRole(dto.getRole());
                membersToInsert.add(member);
            } else {
                // 5. 创建新用户
                User newUser = new User();
                newUser.setUsername(realUsername);
                newUser.setPassword(passwordEncoder.encode(AuthConstant.DEFAULT_PASSWORD));
                newUser.setEnabled(true);
                userMapper.insertUser(newUser);

                // 6. 建立关联
                SchoolMember member = new SchoolMember();
                member.setSchoolId(schoolId);
                member.setUserId(newUser.getId());
                member.setRole(dto.getRole());
                membersToInsert.add(member);
            }
        }

        if (!membersToInsert.isEmpty()) {
            schoolMemberMapper.batchInsert(membersToInsert);
            log.info("成功为学校 {} 添加 {} 名成员", schoolId, membersToInsert.size());
        }
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

        // 既然用户名是绑定学校前缀的，移除了成员身份后，该账号实际上废弃了。
        for (Long userId : userIds) {
            userMapper.deleteUser(userId);
        }

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
            SchoolMember currentMember = schoolMemberMapper.findByUserId(AuthenticationUserUtil.getCurrentUserId());
            Assert.notNull(currentMember, "无权限");
            Assert.isTrue(Objects.equals(currentMember.getSchoolId(), schoolId), "无权限查看其他学校成员");
        }

        return schoolMemberMapper.findMembersBySchoolId(schoolId);
    }
}