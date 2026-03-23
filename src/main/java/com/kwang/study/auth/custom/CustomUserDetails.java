package com.kwang.study.auth.custom;

/**
 * @author kwang
 * @date 2025/08/27
 */
import com.kwang.study.organization.pojo.ClassMember;
import com.kwang.study.organization.pojo.SchoolMember;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import java.util.Collection;
import java.util.List;

@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CustomUserDetails implements UserDetails {
    private static final long serialVersionUID = -4701803906134899675L;
    private Long id;
    private String username;
    private String password;
    private boolean enabled;
    private Collection<? extends GrantedAuthority> authorities;

    // 关键：保存原始的身份列表，供 Filter 动态计算权限
    private List<ClassMember> classMembers;
    private List<SchoolMember> schoolMembers;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}

