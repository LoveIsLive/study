package com.kwang.study.auth.pojo;

import com.kwang.study.organization.pojo.ClassMember;
import com.kwang.study.organization.pojo.SchoolMember;
import lombok.Data;

import java.util.List;

@Data
public class User {
    private Long id;
    private String username;
    private String password;
    private Boolean enabled;
    private List<Role> roles;

    // 目前一个用户(学生/教师)只能有一个班级
    private ClassMember classMember;
    // 目前一个用户(学校领导)只能有一个学校
    private SchoolMember schoolMember;
}
