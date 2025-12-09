package com.kwang.study.llm.config;

import com.alibaba.nacos.api.config.ConfigType;
import com.alibaba.nacos.api.config.annotation.NacosConfigurationProperties;
import lombok.Data;
import org.springframework.context.annotation.Configuration;
import java.util.Map;

@Data
@Configuration
@NacosConfigurationProperties(
        prefix = "kwang.llm",
        dataId = "llm-config",
        groupId = "${nacos.config.group}",
        type = ConfigType.YAML,
        autoRefreshed = true
)
public class LLMGlobalConfig {
    private Map<String, SceneConfig> scenes;

    @Data
    public static class SceneConfig {
        private String apiKey;
        private String baseUrl;
        private String modelName;
        private Double temperature;
        private String systemPrompt;
    }
}