package com.kwang.study.organization.service;

import com.alibaba.excel.EasyExcel;
import com.kwang.study.auth.constant.AuthConstant;
import com.kwang.study.auth.mapper.UserMapper;
import com.kwang.study.auth.pojo.User;
import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.organization.dto.request.ClassMemberAddDTO;
import com.kwang.study.organization.dto.request.ClassMemberExcelDTO;
import com.kwang.study.organization.dto.request.GuestCourseUpdateDTO;
import com.kwang.study.organization.enums.ClassesRoleEnum;
import com.kwang.study.organization.mapper.ClassMemberMapper;
import com.kwang.study.organization.mapper.ClassesMapper;
import com.kwang.study.organization.mapper.CourseGuestMapper;
import com.kwang.study.organization.pojo.ClassMember;
import com.kwang.study.organization.pojo.Classes;
import com.kwang.study.organization.pojo.CourseGuest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.kwang.study.auth.constant.AuthConstant.DEFAULT_PASSWORD;

@Service
@Slf4j
@RequiredArgsConstructor
public class ClassMemberService {

    private final ClassMemberMapper classMemberMapper;
    private final ClassesMapper classesMapper;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserInfoUtils userInfoUtils;
    private final CourseGuestMapper courseGuestMapper; // 新增注入

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
                user.setPassword(passwordEncoder.encode(DEFAULT_PASSWORD));
                user.setEnabled(true);
                user.setPasswordExpired(true);
                userMapper.insertUser(user);
                existingUser = user;
            }

            // 4. 关联班级
            ClassMember member = new ClassMember();
            member.setClassId(classId);
            member.setUserId(existingUser.getId());
            member.setRole(dto.getRole());
            newMembers.add(member);
        }

        if (!newMembers.isEmpty()) {
            userInfoUtils.deleteUsersCache(newMembers.stream()
                    .map(ClassMember::getUserId)
                    .collect(Collectors.toList()));
            classMemberMapper.batchInsert(newMembers);

            // === 新增：如果是访客角色，需要向 course_guests 表插入授权数据 ===
            if (ClassesRoleEnum.GUEST.getRole().equals(dto.getRole())
                    && dto.getAllowedCourseIds() != null
                    && !dto.getAllowedCourseIds().isEmpty()) {

                List<CourseGuest> guestList = new ArrayList<>();
                for (ClassMember cm : newMembers) {
                    for (Long courseId : dto.getAllowedCourseIds()) {
                        CourseGuest cg = new CourseGuest();
                        cg.setCourseId(courseId);
                        cg.setUserId(cm.getUserId());
                        cg.setClassId(classId);
                        guestList.add(cg);
                    }
                }
                if (!guestList.isEmpty()) {
                    courseGuestMapper.batchInsert(guestList);
                }
            }
        }
        return feedbackMessages; // 返回反馈信息
    }

    /**
     * 通过 Excel 导入班级成员
     */
    @Transactional
    public List<String> importMembersFromExcel(Long classId, MultipartFile file) {
        // 1. 校验班级与权限
        Classes classes = classesMapper.findById(classId);
        Assert.notNull(classes, "班级不存在");
        checkWritePermission(classes);

        // 2. 解析 Excel 数据
        List<ClassMemberExcelDTO> dtoList;
        try {
            dtoList = EasyExcel.read(file.getInputStream())
                    .head(ClassMemberExcelDTO.class)
                    .sheet()
                    .doReadSync(); // 同步读取（因为单班级数据量一般不大，直接读入内存）
        } catch (Exception e) {
            log.error("解析Excel失败", e);
            throw new IllegalArgumentException("Excel文件解析失败，请检查文件格式");
        }

        List<String> feedbackMessages = new ArrayList<>();
        List<ClassMember> newMembers = new ArrayList<>();

        // 3. 遍历处理数据
        for (ClassMemberExcelDTO row : dtoList) {
            String rawUsername = row.getUsername();
            // 忽略用户名为空的行
            if (!StringUtils.hasText(rawUsername)) {
                continue;
            }
            rawUsername = rawUsername.trim();

            // 解析密码（为空默认为 123456）
            String rawPassword = StringUtils.hasText(row.getPassword()) ? row.getPassword().trim() : DEFAULT_PASSWORD;

            // 解析角色（为空或不匹配默认为 学生）
            String roleStr = row.getRole();
            String roleEnum = ClassesRoleEnum.STUDENT.getRole();
            if (StringUtils.hasText(roleStr)) {
                roleStr = roleStr.trim();
                if ("教师".equals(roleStr)) {
                    roleEnum = ClassesRoleEnum.TEACHER.getRole();
                } else if ("访客".equals(roleStr)) {
                    roleEnum = ClassesRoleEnum.GUEST.getRole();
                }
            }

            // 4. 检查用户是否存在
            User existingUser = userMapper.findByUsername(rawUsername);
            if (existingUser != null) {
                ClassMember cm = classMemberMapper.findByClassIdAndUserId(classId, existingUser.getId());
                if (cm != null) {
                    feedbackMessages.add("用户 " + rawUsername + " 已在班级中，跳过");
                    continue;
                }
            } else {
                // 创建新用户
                User user = new User();
                user.setUsername(rawUsername);
                user.setPassword(passwordEncoder.encode(rawPassword));
                user.setEnabled(true);
                user.setPasswordExpired(true);
                userMapper.insertUser(user);
                existingUser = user;
            }

            // 5. 建立班级关联
            ClassMember member = new ClassMember();
            member.setClassId(classId);
            member.setUserId(existingUser.getId());
            member.setRole(roleEnum);
            newMembers.add(member);
        }

        // 6. 批量插入关联表
        if (!newMembers.isEmpty()) {
            userInfoUtils.deleteUsersCache(newMembers.stream()
                    .map(ClassMember::getUserId)
                    .collect(Collectors.toList()));

            classMemberMapper.batchInsert(newMembers);
            feedbackMessages.add(0, "成功导入 " + newMembers.size() + " 名新成员");

            // 注意：Excel导入的"访客"默认没有分配课程。如有需求，前端需通过 /guest/{userId}/courses 接口后续分配。
        } else {
            feedbackMessages.add(0, "没有解析到有效的待导入数据");
        }

        return feedbackMessages;
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
        // === 新增：清理被踢出班级的用户的访客课程授权 ===
        courseGuestMapper.deleteByClassIdAndUserIds(classId, userIds);

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

    /**
     * 重新分配访客的课程权限 (全量覆盖)
     */
    @Transactional
    public void updateGuestCourses(Long classId, Long guestUserId, GuestCourseUpdateDTO dto) {
        Classes classes = classesMapper.findById(classId);
        Assert.notNull(classes, "班级不存在");
        checkWritePermission(classes);

        ClassMember cm = classMemberMapper.findByClassIdAndUserId(classId, guestUserId);
        Assert.notNull(cm, "该用户不是本班成员");
        Assert.isTrue(ClassesRoleEnum.GUEST.getRole().equals(cm.getRole()), "只能对访客重新分配课程");

        // 1. 删除旧的授权关系
        courseGuestMapper.deleteByClassIdAndUserId(classId, guestUserId);

        // 2. 插入新的授权关系
        if (dto.getCourseIds() != null && !dto.getCourseIds().isEmpty()) {
            List<CourseGuest> guestList = new ArrayList<>();
            for (Long courseId : dto.getCourseIds()) {
                CourseGuest cg = new CourseGuest();
                cg.setCourseId(courseId);
                cg.setUserId(guestUserId);
                cg.setClassId(classId);
                guestList.add(cg);
            }
            courseGuestMapper.batchInsert(guestList);
        }

        // 3. 强制清理用户缓存，让其 Token 权限立刻刷新
        userInfoUtils.deleteUserCache(guestUserId);
    }


    /**
     * 获取班级的所有访客列表
     */
    public List<ClassMember> getGuestsInClass(Long classId) {
        checkReadPermission(classId);
        return classMemberMapper.findUsersByClassIdAndRole(classId, ClassesRoleEnum.GUEST.getRole());
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