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

    /** 工作流运行参数也由同一个 Nacos dataId 统一管理。 */
    private WorkflowCatalog workflow = new WorkflowCatalog();

    /** 模型未显式声明时使用的 Nacos 全局默认值。 */
    private ModelDefaultsCatalog modelDefaults = new ModelDefaultsCatalog();

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
        private String extraHeadersJson;
        private Boolean reasoningContentFallback;
        private Double temperature;
        private Double topP;
        private Boolean adaptiveThinking;
        private String effort;
        private String thinking;
        private Integer requestTimeoutSeconds;
        private Integer timeoutRetryAttempts;
        private Double timeoutRetryMultiplier;
        private Integer maxRequestTimeoutSeconds;
        private Integer transientFailureRetries;
        private Integer rateLimitRetries;
        private Long rateLimitBaseDelayMillis;
        private Long rateLimitMaxDelayMillis;
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
        private Boolean reasoningContentFallback;
        private Double temperature;
        private Double topP;
        private Boolean adaptiveThinking;
        private String effort;
        private String thinking;
        private String extraHeadersJson;
        private Integer contextWindow;
        private Integer maxOutputTokens;
        private Integer requestTimeoutSeconds;
        private Integer timeoutRetryAttempts;
        private Double timeoutRetryMultiplier;
        private Integer maxRequestTimeoutSeconds;
        private Integer transientFailureRetries;
        private Integer rateLimitRetries;
        private Long rateLimitBaseDelayMillis;
        private Long rateLimitMaxDelayMillis;
    }

    @Data
    public static class WorkflowCatalog {
        private Integer visualDesignSceneMaxRetries = 3;
        private Integer storyboardValidationMaxRetries = 5;
        private Integer placementEnrichmentMaxRetries = 3;
        private Integer storyboardCleanupConversationRounds = 8;
        private Integer placementEnrichmentConversationRounds = 4;
        private Integer codeGenerationMaxRetries = 2;
        private Long codeGenerationRetryDelayMillis = 2_000L;
        private Integer codeEvaluationMaxRetries = 3;
        private Integer codeFixConversationRounds = 4;
        private String renderQuality = "low";
        private Integer renderMaxRetries = 10;
        private Integer sceneEvaluationMaxRetries = 5;
        private Integer renderFixConversationRounds = 10;
        private Boolean aiTraceEnabled = false;
        private Integer aiTraceMaxChars = 200_000;
    }

    @Data
    public static class ModelDefaultsCatalog {
        private Boolean reasoningContentFallback = false;
        private Double temperature = 0.6D;
        private Double topP;
        private Integer requestTimeoutSeconds = 300;
        private Integer timeoutRetryAttempts = 1;
        private Double timeoutRetryMultiplier = 2.0D;
        private Integer maxRequestTimeoutSeconds = 900;
        private Integer emptyResponseRetries = 2;
        private Integer transientFailureRetries = 2;
        private Long transientRetryBaseDelayMillis = 1_000L;
        private Long transientRetryMaxDelayMillis = 4_000L;
        private Integer rateLimitRetries = 12;
        private Long rateLimitBaseDelayMillis = 5_000L;
        private Long rateLimitMaxDelayMillis = 300_000L;
        private Double rateLimitJitterRatio = 0.25D;
    }
}
