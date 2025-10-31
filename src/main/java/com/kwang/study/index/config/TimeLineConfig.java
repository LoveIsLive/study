package com.kwang.study.index.config;

import com.alibaba.nacos.api.config.ConfigType;
import com.alibaba.nacos.api.config.annotation.NacosConfigurationProperties;
import com.kwang.study.index.pojo.TimeLineItem;
import lombok.Data;
import lombok.Getter;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * @author kwang
 * @date 2025/09/03
 */
@NacosConfigurationProperties(
        prefix = "kwang.index",
        dataId = "index",
        groupId = "${nacos.config.group}",
        type = ConfigType.YAML,
        autoRefreshed = true)
@Data
@Configuration
public class TimeLineConfig {
    private Map<String, List<TimeLineItem>> timeline;
}
