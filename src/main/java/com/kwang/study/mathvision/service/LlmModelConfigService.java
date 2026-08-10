package com.kwang.study.mathvision.service;

import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.config.MathVisionModelCatalog.ModelCatalog;
import com.kwang.study.mathvision.config.MathVisionModelCatalog.ProviderCatalog;
import com.kwang.study.mathvision.dto.CredentialTestResultVO;
import com.kwang.study.mathvision.dto.CustomProviderConfigDTO;
import com.kwang.study.mathvision.dto.LlmModelDTO;
import com.kwang.study.mathvision.dto.ProviderInfoVO;
import com.kwang.study.mathvision.dto.ProviderModelsVO;
import com.kwang.study.mathvision.enums.ProviderEnum;
import com.kwang.study.mathvision.mapper.LlmModelConfigMapper;
import com.kwang.study.mathvision.pojo.LlmModelConfig;
import com.kwang.study.mathvision.provider.LlmProviderAdapter;
import com.kwang.study.mathvision.provider.ProviderAdapterRegistry;
import com.kwang.study.mathvision.provider.ProviderProbeResult;
import com.kwang.study.mathvision.util.ApiKeyCipher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * LLM 模型 / API Key 配置服务。
 * 供应商与模型目录来自 Nacos (MathVisionModelCatalog); 用户 API Key 存 DB。
 */
@Service
public class LlmModelConfigService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LlmModelConfigMapper configMapper;
    private final ApiKeyCipher cipher;
    private final ProviderAdapterRegistry adapterRegistry;
    private final MathVisionModelCatalog catalog;

    public LlmModelConfigService(LlmModelConfigMapper configMapper,
                                 ApiKeyCipher cipher,
                                 ProviderAdapterRegistry adapterRegistry,
                                 MathVisionModelCatalog catalog) {
        this.configMapper = configMapper;
        this.cipher = cipher;
        this.adapterRegistry = adapterRegistry;
        this.catalog = catalog;
    }

    /** 列出目录中启用的供应商 + 当前用户的配置状态。 */
    public List<ProviderInfoVO> listProviders(Long userId) {
        List<ProviderInfoVO> result = new ArrayList<>();
        for (ProviderCatalog provider : catalog.enabledProviders()) {
            LlmModelConfig cfg = configMapper.findByOwnerAndProvider(userId, provider.getCode());
            result.add(toInfoVO(provider, cfg));
        }
        List<LlmModelConfig> customConfigs = configMapper.findCustomByOwner(userId);
        if (customConfigs != null) {
            for (LlmModelConfig config : customConfigs) {
                result.add(toCustomInfoVO(config));
            }
        }
        return result;
    }

    /** 返回某供应商目录中的可用模型列表 (含能力声明)。 */
    public ProviderModelsVO listModels(Long userId, String providerCode) {
        LlmModelConfig custom = configMapper.findByOwnerAndProvider(userId, providerCode);
        if (isCustom(custom)) {
            return ProviderModelsVO.builder()
                    .providerCode(custom.getProvider())
                    .models(List.of(toCustomModelDTO(custom)))
                    .build();
        }
        ProviderCatalog provider = requireCatalogProvider(providerCode);
        List<LlmModelDTO> models = new ArrayList<>();
        if (provider.getModels() != null) {
            for (ModelCatalog m : provider.getModels()) {
                models.add(toModelDTO(provider.getCode(), m));
            }
        }
        return ProviderModelsVO.builder()
                .providerCode(provider.getCode())
                .models(models)
                .build();
    }

    /** 设置 / 更新厂家 API Key。 */
    public ProviderInfoVO upsertCredential(Long userId, String providerCode, String apiKey) {
        ProviderCatalog provider = requireCatalogProvider(providerCode);
        String encrypted = cipher.encrypt(apiKey.trim());
        String masked = cipher.mask(apiKey.trim());

        LlmModelConfig existing = configMapper.findByOwnerAndProvider(userId, provider.getCode());
        if (existing == null) {
            LlmModelConfig cfg = LlmModelConfig.builder()
                    .ownerUserId(userId)
                    .provider(provider.getCode())
                    .isCustom(false)
                    .apiKeyEncrypted(encrypted)
                    .apiKeyMasked(masked)
                    .status("enabled")
                    .build();
            configMapper.insert(cfg);
            return toInfoVO(provider, cfg);
        }
        existing.setApiKeyEncrypted(encrypted);
        existing.setApiKeyMasked(masked);
        existing.setStatus("enabled");
        configMapper.updateCredential(existing);
        return toInfoVO(provider, existing);
    }

    public ProviderInfoVO createCustomProvider(Long userId, CustomProviderConfigDTO request) {
        if (!StringUtils.hasText(request.getApiKey())) {
            throw new IllegalArgumentException("请输入 API Key");
        }
        LlmModelConfig config = newCustomConfig(userId, request);
        configMapper.insert(config);
        return toCustomInfoVO(config);
    }

    public ProviderInfoVO updateCustomProvider(Long userId,
                                               String providerCode,
                                               CustomProviderConfigDTO request) {
        LlmModelConfig config = requireOwnedCustom(userId, providerCode);
        applyCustomFields(config, request);
        if (StringUtils.hasText(request.getApiKey())) {
            String apiKey = request.getApiKey().trim();
            config.setApiKeyEncrypted(cipher.encrypt(apiKey));
            config.setApiKeyMasked(cipher.mask(apiKey));
        }
        if (!StringUtils.hasText(config.getApiKeyEncrypted())) {
            throw new IllegalArgumentException("请输入 API Key");
        }
        config.setStatus("enabled");
        if (configMapper.updateCustom(config) == 0) {
            throw new IllegalArgumentException("自定义模型配置已变化，请刷新后重试");
        }
        return toCustomInfoVO(config);
    }

    /** 删除厂家 API Key; 只删当前用户自己的, 历史任务不受影响。 */
    public void deleteCredential(Long userId, String providerCode) {
        LlmModelConfig existing = configMapper.findByOwnerAndProvider(userId, providerCode);
        if (isCustom(existing)) {
            configMapper.deleteByOwnerAndProvider(userId, existing.getProvider());
            return;
        }
        ProviderCatalog provider = requireCatalogProvider(providerCode);
        configMapper.deleteByOwnerAndProvider(userId, provider.getCode());
    }

    /** 测试厂家 API Key; baseUrl 取自目录, adapter 按 provider 分派。 */
    public CredentialTestResultVO testCredential(Long userId, String providerCode) {
        LlmModelConfig cfg = configMapper.findByOwnerAndProvider(userId, providerCode);
        ProviderCatalog provider = isCustom(cfg) ? null : requireCatalogProvider(providerCode);
        String resolvedProviderCode = provider != null ? provider.getCode() : providerCode;
        if (cfg == null || cfg.getApiKeyEncrypted() == null) {
            return CredentialTestResultVO.builder()
                    .providerCode(resolvedProviderCode)
                    .success(false)
                    .status("not_configured")
                    .message("尚未配置 API Key")
                    .build();
        }

        ProviderEnum providerEnum = ProviderEnum.fromCode(
                isCustom(cfg) ? cfg.getCompatibilityType() : provider.getCode());
        LlmProviderAdapter adapter = adapterRegistry.get(providerEnum);
        if (adapter == null) {
            return CredentialTestResultVO.builder()
                    .providerCode(resolvedProviderCode)
                    .success(false)
                    .status("invalid")
                    .message("该厂家暂不支持测试")
                    .build();
        }

        String apiKey = cipher.decrypt(cfg.getApiKeyEncrypted());
        String baseUrl = isCustom(cfg) ? cfg.getBaseUrl() : provider.getBaseUrl();
        ProviderProbeResult probe = adapter.testCredential(apiKey, baseUrl);

        String status = probe.isSuccess() ? "enabled" : "invalid";
        cfg.setStatus(status);
        cfg.setLastTestTime(LocalDateTime.now());
        cfg.setLastTestResult(truncate(probe.getMessage()));
        configMapper.updateTestResult(cfg);

        return CredentialTestResultVO.builder()
                .providerCode(resolvedProviderCode)
                .success(probe.isSuccess())
                .status(status)
                .message(probe.isSuccess() ? "API Key 可用" : probe.getMessage())
                .build();
    }

    private LlmModelDTO toModelDTO(String providerCode, ModelCatalog m) {
        return LlmModelDTO.builder()
                .providerCode(providerCode)
                .modelName(m.getModelName())
                .displayName(m.getDisplayName() != null ? m.getDisplayName() : m.getModelName())
                .supportVision(m.getSupportVision())
                .supportJsonOutput(m.getSupportJsonOutput())
                .supportThinking(m.getSupportThinking())
                .contextWindow(m.getContextWindow())
                .maxOutputTokens(m.getMaxOutputTokens())
                .build();
    }

    private ProviderInfoVO toInfoVO(ProviderCatalog provider, LlmModelConfig cfg) {
        boolean configured = cfg != null && cfg.getApiKeyEncrypted() != null;
        return ProviderInfoVO.builder()
                .providerCode(provider.getCode())
                .providerName(provider.getName())
                .configured(configured)
                .status(configured ? (cfg.getStatus() == null ? "enabled" : cfg.getStatus()) : "not_configured")
                .apiKeyMasked(configured ? cfg.getApiKeyMasked() : null)
                .lastTestTime(cfg != null && cfg.getLastTestTime() != null ? cfg.getLastTestTime().format(TS) : null)
                .custom(false)
                .build();
    }

    private ProviderInfoVO toCustomInfoVO(LlmModelConfig cfg) {
        boolean configured = cfg != null && StringUtils.hasText(cfg.getApiKeyEncrypted());
        return ProviderInfoVO.builder()
                .providerCode(cfg.getProvider())
                .providerName(cfg.getProviderName())
                .configured(configured)
                .status(configured ? defaultStatus(cfg.getStatus()) : "not_configured")
                .apiKeyMasked(configured ? cfg.getApiKeyMasked() : null)
                .lastTestTime(cfg.getLastTestTime() != null ? cfg.getLastTestTime().format(TS) : null)
                .custom(true)
                .compatibilityType(cfg.getCompatibilityType())
                .baseUrl(cfg.getBaseUrl())
                .modelName(cfg.getModelName())
                .supportVision(Boolean.TRUE.equals(cfg.getSupportVision()))
                .contextWindow(cfg.getContextWindow())
                .maxOutputTokens(cfg.getMaxOutputTokens())
                .temperature(cfg.getTemperature())
                .topP(cfg.getTopP())
                .build();
    }

    private LlmModelDTO toCustomModelDTO(LlmModelConfig cfg) {
        return LlmModelDTO.builder()
                .providerCode(cfg.getProvider())
                .modelName(cfg.getModelName())
                .displayName(cfg.getModelName())
                .supportVision(Boolean.TRUE.equals(cfg.getSupportVision()))
                .supportJsonOutput(true)
                .supportThinking(false)
                .contextWindow(cfg.getContextWindow())
                .maxOutputTokens(cfg.getMaxOutputTokens())
                .build();
    }

    private LlmModelConfig newCustomConfig(Long userId, CustomProviderConfigDTO request) {
        String providerCode;
        do {
            providerCode = "custom_" + UUID.randomUUID().toString().replace("-", "");
        } while (configMapper.findByOwnerAndProvider(userId, providerCode) != null);

        String apiKey = request.getApiKey().trim();
        LlmModelConfig config = LlmModelConfig.builder()
                .ownerUserId(userId)
                .provider(providerCode)
                .isCustom(true)
                .apiKeyEncrypted(cipher.encrypt(apiKey))
                .apiKeyMasked(cipher.mask(apiKey))
                .status("enabled")
                .build();
        applyCustomFields(config, request);
        return config;
    }

    private void applyCustomFields(LlmModelConfig config, CustomProviderConfigDTO request) {
        config.setProviderName(request.getProviderName().trim());
        config.setCompatibilityType(normalizeCompatibilityType(request.getCompatibilityType()));
        config.setBaseUrl(normalizeBaseUrl(request.getBaseUrl()));
        config.setModelName(request.getModelName().trim());
        config.setSupportVision(Boolean.TRUE.equals(request.getSupportVision()));
        config.setContextWindow(request.getContextWindow() != null ? request.getContextWindow() : 128_000);
        config.setMaxOutputTokens(request.getMaxOutputTokens() != null ? request.getMaxOutputTokens() : 16_384);
        if (config.getMaxOutputTokens() > config.getContextWindow()) {
            throw new IllegalArgumentException("最大输出 Token 数不能大于上下文窗口");
        }
        config.setTemperature(request.getTemperature());
        config.setTopP(request.getTopP());
    }

    private String normalizeCompatibilityType(String value) {
        String type = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (ProviderEnum.fromCode(type) == null
                || (!"openai".equals(type) && !"anthropic".equals(type) && !"google".equals(type))) {
            throw new IllegalArgumentException("仅支持 OpenAI、Anthropic 或 Gemini 兼容协议");
        }
        return type;
    }

    private String normalizeBaseUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("https".equals(scheme) || "http".equals(scheme))
                    || !StringUtils.hasText(uri.getHost())
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException("Base URL 必须是有效的 HTTP(S) API 根地址");
            }
            rejectLocalAddress(uri.getHost());
            return value.trim().replaceAll("/+$", "");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Base URL 格式不正确", e);
        }
    }

    private void rejectLocalAddress(String host) throws Exception {
        String normalized = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalized)
                || normalized.endsWith(".localhost")
                || normalized.endsWith(".local")
                || normalized.endsWith(".internal")) {
            throw new IllegalArgumentException("Base URL 不允许指向本机或内网地址");
        }
        if (normalized.matches("^[0-9.]+$") || normalized.contains(":")) {
            InetAddress address = InetAddress.getByName(normalized);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                throw new IllegalArgumentException("Base URL 不允许指向本机或内网地址");
            }
        }
    }

    private LlmModelConfig requireOwnedCustom(Long userId, String providerCode) {
        LlmModelConfig config = configMapper.findByOwnerAndProvider(userId, providerCode);
        if (!isCustom(config)) {
            throw new IllegalArgumentException("自定义模型配置不存在");
        }
        return config;
    }

    private boolean isCustom(LlmModelConfig config) {
        return config != null && Boolean.TRUE.equals(config.getIsCustom());
    }

    private String defaultStatus(String status) {
        return StringUtils.hasText(status) ? status : "enabled";
    }

    private ProviderCatalog requireCatalogProvider(String providerCode) {
        ProviderCatalog provider = catalog.findEnabled(providerCode);
        if (provider == null) {
            throw new IllegalArgumentException("不支持或未启用的模型厂家: " + providerCode);
        }
        return provider;
    }

    private String truncate(String msg) {
        if (msg == null) {
            return null;
        }
        return msg.length() > 255 ? msg.substring(0, 255) : msg;
    }
}
