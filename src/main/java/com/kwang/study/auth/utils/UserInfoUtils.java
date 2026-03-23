package com.kwang.study.auth.utils;

import com.kwang.study.auth.mapper.UserMapper;
import com.kwang.study.auth.pojo.User;
import com.kwang.study.organization.enums.ClassesRoleEnum;
import com.kwang.study.organization.enums.SchoolRoleEnum;
import com.kwang.study.organization.mapper.ClassesMapper;
import com.kwang.study.organization.pojo.ClassMember;
import com.kwang.study.organization.pojo.Classes;
import com.kwang.study.organization.pojo.SchoolMember;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Component
public class UserInfoUtils {
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private RedisTemplate<String, Object> redis;
    @Autowired
    private HttpServletRequest request;

    @Autowired
    private ClassesMapper classesMapper;

    private static final ThreadLocal<Long> manualClassId = new ThreadLocal<>();
    private static final ThreadLocal<Long> manualSchoolId = new ThreadLocal<>();

    public static final String USERINFO_PREFIX = "userinfo:";

    // 提供给手动设置的方法
    public void setManualContext(Long classId, Long schoolId) {
        manualClassId.set(classId);
        manualSchoolId.set(schoolId);
    }

    public void clearManualContext() {
        manualClassId.remove();
        manualSchoolId.remove();
    }

    public User getCurrentUserInfoWithOrgInfo() {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        if (userId == null) return null;

        User user = (User) redis.opsForValue().get(USERINFO_PREFIX + userId);
        if (user != null) return user;

        user = userMapper.findByIdWithOrgInfo(userId);
        if (user != null) {
            redis.opsForValue().set(USERINFO_PREFIX + userId, user, 5, TimeUnit.MINUTES);
        }
        return user;
    }

    /**
     * 获取当前激活的班级身份
     */
    public ClassMember getCurrentActiveClassMember() {
        User user = getCurrentUserInfoWithOrgInfo();
        if (user == null || CollectionUtils.isEmpty(user.getClassMembers())) return null;

        // 1. 优先尝试从手动设置的 ThreadLocal 获取
        Long targetClassId = manualClassId.get();

        // 2. 如果没有（说明是 HTTP 环境），尝试从 Header 获取
        if (targetClassId == null) {
            try {
                // 这里的 request 注入在 WebSocket 线程会抛异常，需要捕获
                String headerId = request.getHeader("X-Active-Class-Id");
                if (headerId != null) targetClassId = Long.parseLong(headerId);
            } catch (Exception e) {
                // 非 HTTP 环境且未手动设置 ThreadLocal
            }
        }

        if (targetClassId == null) return null;

        Long finalId = targetClassId;
        return user.getClassMembers().stream()
                .filter(cm -> cm.getClassId().equals(finalId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取当前激活的学校身份 (针对校长)
     */
    public SchoolMember getCurrentActiveSchoolMember() {
        User user = getCurrentUserInfoWithOrgInfo();
        if (user == null || CollectionUtils.isEmpty(user.getSchoolMembers())) return null;

        Long targetSchoolId = manualSchoolId.get();

        if (targetSchoolId == null) {
            try {
                String headerId = request.getHeader("X-Active-School-Id");
                if (headerId != null) targetSchoolId = Long.parseLong(headerId);
            } catch (Exception e) {
                // 非 HTTP 环境且未手动设置 ThreadLocal
            }
        }

        if (targetSchoolId == null) return null;

        Long finalId = targetSchoolId;
        return user.getSchoolMembers().stream()
                .filter(cm -> cm.getSchoolId().equals(finalId))
                .findFirst()
                .orElse(null);
    }

    public boolean currentUserInClassIsTeacher() {
        ClassMember active = getCurrentActiveClassMember();
        return active != null && ClassesRoleEnum.TEACHER.getRole().equals(active.getRole());
    }

    public boolean currentUserInClassIsStudent() {
        ClassMember active = getCurrentActiveClassMember();
        return active != null && ClassesRoleEnum.STUDENT.getRole().equals(active.getRole());
    }

    public boolean currentUserInSchoolIsPrincipal() {
        SchoolMember active = getCurrentActiveSchoolMember();
        return active != null && SchoolRoleEnum.PRINCIPAL.getRole().equals(active.getRole());
    }

    // 当前选择身份在目标学校。校长、教师、学生
    public boolean inSchool(Long tagetSchoolId) {
        SchoolMember active = getCurrentActiveSchoolMember();
        if (active != null && Objects.equals(active.getSchoolId(), tagetSchoolId))
            return true;
        ClassMember aCM = getCurrentActiveClassMember();
        return aCM != null && Objects.equals(aCM.getClasses().getSchoolId(), tagetSchoolId);
    }

    // 当前选择身份在目标班级
    public boolean inClass(Long tagetClassId) {
        ClassMember active = getCurrentActiveClassMember();
        return active != null && Objects.equals(active.getClassId(), tagetClassId);
    }

    // 当前选择身份是目标学校的校长
    public boolean inSchoolPrincipal(Long tagetSchoolId) {
        SchoolMember active = getCurrentActiveSchoolMember();
        return active != null && Objects.equals(active.getRole(), SchoolRoleEnum.PRINCIPAL.getRole()) &&
                Objects.equals(active.getSchoolId(), tagetSchoolId);
    }

    // 当前选择身份是目标班级所属学校的校长
    public boolean inClassOfSchoolPrincipal(Long targetClassId) {
        Classes classes = classesMapper.findById(targetClassId);
        return inSchoolPrincipal(classes.getSchoolId());
    }

    // 当前选择身份是目标班级的教师
    public boolean inClassTeacher(Long tagetClassId) {
        ClassMember active = getCurrentActiveClassMember();
        return active != null && Objects.equals(active.getRole(), ClassesRoleEnum.TEACHER.getRole()) &&
                Objects.equals(active.getClassId(), tagetClassId);
    }

    // 当前选择身份是目标班级的学生
    public boolean inClassStudent(Long tagetClassId) {
        ClassMember active = getCurrentActiveClassMember();
        return active != null && Objects.equals(active.getRole(), ClassesRoleEnum.STUDENT.getRole()) &&
                Objects.equals(active.getClassId(), tagetClassId);
    }
}