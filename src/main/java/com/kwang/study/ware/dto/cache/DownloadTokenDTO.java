package com.kwang.study.ware.dto.cache;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
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
    private Collection<? extends GrantedAuthority> authorities;
}
