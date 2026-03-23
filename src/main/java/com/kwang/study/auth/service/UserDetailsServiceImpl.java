package com.kwang.study.auth.service;

import com.kwang.study.auth.custom.CustomUserDetails;
import com.kwang.study.auth.mapper.UserMapper;
import com.kwang.study.auth.pojo.Role;
import com.kwang.study.auth.pojo.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    @Autowired
    private UserMapper userMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userMapper.findByUsernameWithOrgInfo(username);
        if (user == null) {
            throw new UsernameNotFoundException("User not found with username: " + username);
        }

        List<SimpleGrantedAuthority> baseAuthorities = new ArrayList<>();
        if (!CollectionUtils.isEmpty(user.getRoles())) {
            user.getRoles().forEach(r -> baseAuthorities.add(new SimpleGrantedAuthority(r.getName())));
        }

        return CustomUserDetails.builder()
                .id(user.getId())
                .username(username)
                .password(user.getPassword())
                .enabled(user.getEnabled())
                .classMembers(user.getClassMembers())   // 存储所有班级身份
                .schoolMembers(user.getSchoolMembers()) // 存储所有学校身份
                .authorities(baseAuthorities)           // 初始权限
                .build();
    }

}
