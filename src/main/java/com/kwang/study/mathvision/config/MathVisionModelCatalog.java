package com.kwang.study.mathvision.config;

import com.alibaba.nacos.api.config.ConfigType;
import com.alibaba.nacos.api.config.annotation.NacosConfigurationProperties;
import lombok.Data;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * MathVision 模型目录 (Nacos dataId: math-vision, YAML)。
 * 声明有哪些供应商、每家有哪些模型及其能力; 用户只配置 API Key, 不配 baseUrl。
 * 能力字段为声明值, 直接作为创建任务校验与前端展示依据。
 */
@Data
@Configuration
@NacosConfigurationProperties(
        dataId = "math-vision",
        groupId = "${nacos.config.group}",
        type = ConfigType.YAML,
        autoRefreshed = true
)
public class MathVisionModelCatalog {

    /** 对应配置根键 model-providers */
    private List<ProviderCatalog> modelProviders = new ArrayList<>();

    /** 返回启用的供应商 */
    public List<ProviderCatalog> enabledProviders() {
        List<ProviderCatalog> list = new ArrayList<>();
        for (ProviderCatalog p : modelProviders) {
            if (Boolean.TRUE.equals(p.getEnabled())) {
                list.add(p);
            }
        }
        return list;
    }

    /** 按 code 查供应商; 不存在或未启用返回 null。 */
    public ProviderCatalog findEnabled(String code) {
        if (code == null) {
            return null;
        }
        for (ProviderCatalog p : modelProviders) {
            if (code.equalsIgnoreCase(p.getCode()) && Boolean.TRUE.equals(p.getEnabled())) {
                return p;
            }
        }
        return null;
    }

    /** 在某供应商下按模型名查模型; 不存在返回 null。 */
    public ModelCatalog findModel(String providerCode, String modelName) {
        ProviderCatalog p = findEnabled(providerCode);
        if (p == null || modelName == null) {
            return null;
        }
        for (ModelCatalog m : p.getModels()) {
            if (modelName.equals(m.getModelName())) {
                return m;
            }
        }
        return null;
    }

    @Data
    public static class ProviderCatalog {
        private String code;
        private String name;
        private String baseUrl;
        private Boolean enabled;
        private List<ModelCatalog> models = new ArrayList<>();
    }

    @Data
    public static class ModelCatalog {
        private String modelName;
        private String displayName;
        private Boolean supportVision;
        private Boolean supportJsonOutput;
        private Boolean supportThinking;
        private Integer contextWindow;
        private Integer maxOutputTokens;
    }
}
