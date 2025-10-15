package com.kwang.study.ware.dto.cache;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.context.SecurityContext;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DownloadTokenDTO {
    private String path;
    private String username;
}
