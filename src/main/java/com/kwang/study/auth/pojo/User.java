package com.kwang.study.auth.pojo;

import com.kwang.study.organization.pojo.ClassMember;
import lombok.Data;

import java.util.List;

@Data
public class User {
    private Long id;
    private String username;
    private String password;
    private Boolean enabled;
    private List<Role> roles;

    // 目前一个用户只能有一个班级
    private ClassMember classMember;
}
