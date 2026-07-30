package com.kwang.study.auth.config;

import com.alibaba.nacos.api.config.ConfigType;
import com.alibaba.nacos.api.config.annotation.NacosConfigurationProperties;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


@Component
@ConfigurationProperties(prefix = "kwang.jwt")
@NacosConfigurationProperties(
        prefix = "kwang.jwt",
        dataId = "study",
        groupId = "${nacos.config.group}",
        type = ConfigType.YAML,
        autoRefreshed = true
)
@Data
public class JWTConfig {
    @ToString.Exclude
    private String security;
    private Long expiration;
}
