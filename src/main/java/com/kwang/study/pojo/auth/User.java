package com.kwang.study.pojo.auth;

import lombok.Data;

import java.util.List;

@Data
public class User {
    private Long id;
    private String username;
    private String password;
    private boolean enabled;
    private List<Role> roles;
}
