package com.kwang.study.mapper.auth;

import com.kwang.study.pojo.auth.Role;
import com.kwang.study.pojo.auth.User;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface UserMapper {
    User findByUsername(String username);

    List<Role> findRolesByUserId(Long userId);
}
