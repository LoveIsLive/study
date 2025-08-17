package com.kwang.study.auth.mapper;

import com.kwang.study.auth.pojo.Role;
import com.kwang.study.auth.pojo.User;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface UserMapper {
    User findByUsername(String username);

    List<Role> findRolesByUserId(Long userId);
}
