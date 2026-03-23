package com.kwang.study.ware.dto.cache;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DownloadTokenDTO implements Serializable {
    private static final long serialVersionUID = 2605387122960702884L;

    private String path;
    private String username;
    private Long activeSM;
    private Long activeCM;
    private Collection<? extends GrantedAuthority> authorities;
}
