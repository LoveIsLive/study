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
    private Boolean passwordExpired;

    private List<Role> roles;

    private List<ClassMember> classMembers;
    private List<SchoolMember> schoolMembers;
}
