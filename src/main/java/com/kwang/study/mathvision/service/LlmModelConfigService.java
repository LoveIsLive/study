package com.kwang.study.mathvision.service;

import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.config.MathVisionModelCatalog.ModelCatalog;
import com.kwang.study.mathvision.config.MathVisionModelCatalog.ProviderCatalog;
import com.kwang.study.mathvision.dto.CredentialTestResultVO;
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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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
        return result;
    }

    /** 返回某供应商目录中的可用模型列表 (含能力声明)。 */
    public ProviderModelsVO listModels(String providerCode) {
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

    /** 删除厂家 API Key; 只删当前用户自己的, 历史任务不受影响。 */
    public void deleteCredential(Long userId, String providerCode) {
        ProviderCatalog provider = requireCatalogProvider(providerCode);
        configMapper.deleteByOwnerAndProvider(userId, provider.getCode());
    }

    /** 测试厂家 API Key; baseUrl 取自目录, adapter 按 provider 分派。 */
    public CredentialTestResultVO testCredential(Long userId, String providerCode) {
        ProviderCatalog provider = requireCatalogProvider(providerCode);
        LlmModelConfig cfg = configMapper.findByOwnerAndProvider(userId, provider.getCode());
        if (cfg == null || cfg.getApiKeyEncrypted() == null) {
            return CredentialTestResultVO.builder()
                    .providerCode(provider.getCode())
                    .success(false)
                    .status("not_configured")
                    .message("尚未配置 API Key")
                    .build();
        }

        ProviderEnum providerEnum = ProviderEnum.fromCode(provider.getCode());
        LlmProviderAdapter adapter = adapterRegistry.get(providerEnum);
        if (adapter == null) {
            return CredentialTestResultVO.builder()
                    .providerCode(provider.getCode())
                    .success(false)
                    .status("invalid")
                    .message("该厂家暂不支持测试")
                    .build();
        }

        String apiKey = cipher.decrypt(cfg.getApiKeyEncrypted());
        ProviderProbeResult probe = adapter.testCredential(apiKey, provider.getBaseUrl());

        String status = probe.isSuccess() ? "enabled" : "invalid";
        cfg.setStatus(status);
        cfg.setLastTestTime(LocalDateTime.now());
        cfg.setLastTestResult(truncate(probe.getMessage()));
        configMapper.updateTestResult(cfg);

        return CredentialTestResultVO.builder()
                .providerCode(provider.getCode())
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
                .build();
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
