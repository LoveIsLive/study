package com.kwang.study.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


@Component
@ConfigurationProperties(prefix = "kwang.jwt")
@Data
public class JWTConfig {
    private String security;
    private Long expiration;
}