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

        List<GrantedAuthority> authorities = new ArrayList<>();
        // 目前仅有特殊的admin管理员权限
        if (!CollectionUtils.isEmpty(user.getRoles())) {
            for (Role role : user.getRoles()) {
                if ("ROLE_ADMIN".equals(role.getName())) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                    break;
                }
            }
        }
        // 在学校-班级内的角色，前端使用
        // 在班级内的角色
        if (user.getClassMember() != null && user.getClassMember().getRole() != null) {
            authorities.add(new SimpleGrantedAuthority(user.getClassMember().getRole()));
        }
        // 在学校层的角色
        if (user.getSchoolMember() != null && user.getSchoolMember().getRole() != null) {
            authorities.add(new SimpleGrantedAuthority(user.getSchoolMember().getRole()));
        }

        return new CustomUserDetails(user.getId(), username,
                user.getPassword(), user.getEnabled(), authorities);
    }

}
