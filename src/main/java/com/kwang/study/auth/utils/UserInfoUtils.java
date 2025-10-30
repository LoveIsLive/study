package com.kwang.study.auth.utils;

import com.kwang.study.auth.mapper.UserMapper;
import com.kwang.study.auth.pojo.User;
import com.kwang.study.organization.enums.ClassesRoleEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class UserInfoUtils {
    @Autowired
    private UserMapper userMapper;

    public boolean currentUserInClassIsTeacher() {
        String userName = AuthenticationUserUtil.getCurrentUserName();
        if (userName == null) return false;

        User user = userMapper.findByUsernameWithClasses(userName);
        String role = user.getClassMember().getRole();
        return ClassesRoleEnum.TEACHER.getRole().equals(role);
    }

    public boolean currentUserInClassIsStudent() {
        String userName = AuthenticationUserUtil.getCurrentUserName();
        if (userName == null) return false;

        User user = userMapper.findByUsernameWithClasses(userName);
        String role = user.getClassMember().getRole();
        return ClassesRoleEnum.STUDENT.getRole().equals(role);
    }

    public User getCurrentUserInfoWithClasses() {
        String userName = AuthenticationUserUtil.getCurrentUserName();
        if (userName == null) return null;

        return userMapper.findByUsernameWithClasses(userName);
    }
}
