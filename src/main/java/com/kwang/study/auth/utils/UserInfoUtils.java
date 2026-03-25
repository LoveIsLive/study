package com.kwang.study.auth.utils;

import com.kwang.study.auth.mapper.UserMapper;
import com.kwang.study.auth.pojo.User;
import com.kwang.study.organization.enums.ClassesRoleEnum;
import com.kwang.study.organization.enums.SchoolRoleEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpSession;

@Component
public class UserInfoUtils {
    @Autowired
    private UserMapper userMapper;

    public boolean currentUserInClassIsTeacher() {
        User user = this.getCurrentUserInfoWithOrgInfo();
        if (user == null || user.getClassMember() == null) return false;

        String role = user.getClassMember().getRole();
        return ClassesRoleEnum.TEACHER.getRole().equals(role);
    }

    public boolean currentUserInClassIsStudent() {
        User user = this.getCurrentUserInfoWithOrgInfo();
        if (user == null || user.getClassMember() == null) return false;

        String role = user.getClassMember().getRole();
        return ClassesRoleEnum.STUDENT.getRole().equals(role);
    }

    public boolean currentUserInSchoolIsPrincipal() {
        User user = this.getCurrentUserInfoWithOrgInfo();
        if (user == null || user.getSchoolMember() == null) return false;

        String role = user.getSchoolMember().getRole();
        return SchoolRoleEnum.PRINCIPAL.getRole().equals(role);
    }

    public User getCurrentUserInfoWithOrgInfo() {
        String userName = AuthenticationUserUtil.getCurrentUserName();
        if (userName == null) return null;

        ServletRequestAttributes attr = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        // 存入session进行缓存
        HttpSession session = attr.getRequest().getSession(true); // true 表示如果不存在则创建
        if (session.getAttribute("auth#currentUserInfoWithOrgInfo") != null) {
            return (User) session.getAttribute("auth#currentUserInfoWithOrgInfo");
        }

        User userWithOrgInfo = userMapper.findByUsernameWithOrgInfo(userName);
        session.setAttribute("auth#currentUserInfoWithOrgInfo", userWithOrgInfo);
        return userWithOrgInfo;
    }
}
