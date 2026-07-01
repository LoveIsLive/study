package com.kwang.study.mathvision.service;

import com.kwang.study.mathvision.dto.CredentialTestResultVO;
import com.kwang.study.mathvision.dto.ProviderInfoVO;
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
 */
@Service
public class LlmModelConfigService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LlmModelConfigMapper configMapper;
    private final ApiKeyCipher cipher;
    private final ProviderAdapterRegistry adapterRegistry;

    public LlmModelConfigService(LlmModelConfigMapper configMapper,
                                 ApiKeyCipher cipher,
                                 ProviderAdapterRegistry adapterRegistry) {
        this.configMapper = configMapper;
        this.cipher = cipher;
        this.adapterRegistry = adapterRegistry;
    }

    /** 列出全部固定厂家 + 当前用户配置状态。 */
    public List<ProviderInfoVO> listProviders(Long userId) {
        List<ProviderInfoVO> result = new ArrayList<>();
        for (ProviderEnum provider : ProviderEnum.values()) {
            LlmModelConfig cfg = configMapper.findByOwnerAndProvider(userId, provider.getCode());
            result.add(toInfoVO(provider, cfg));
        }
        return result;
    }

    /** 设置 / 更新厂家 API Key。 */
    public ProviderInfoVO upsertCredential(Long userId, String providerCode, String apiKey) {
        ProviderEnum provider = requireProvider(providerCode);
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
        ProviderEnum provider = requireProvider(providerCode);
        configMapper.deleteByOwnerAndProvider(userId, provider.getCode());
    }

    /** 测试厂家 API Key。 */
    public CredentialTestResultVO testCredential(Long userId, String providerCode) {
        ProviderEnum provider = requireProvider(providerCode);
        LlmModelConfig cfg = configMapper.findByOwnerAndProvider(userId, provider.getCode());
        if (cfg == null || cfg.getApiKeyEncrypted() == null) {
            return CredentialTestResultVO.builder()
                    .providerCode(provider.getCode())
                    .success(false)
                    .status("not_configured")
                    .message("尚未配置 API Key")
                    .build();
        }

        LlmProviderAdapter adapter = adapterRegistry.get(provider);
        if (adapter == null) {
            return CredentialTestResultVO.builder()
                    .providerCode(provider.getCode())
                    .success(false)
                    .status("invalid")
                    .message("该厂家暂不支持测试")
                    .build();
        }

        String apiKey = cipher.decrypt(cfg.getApiKeyEncrypted());
        ProviderProbeResult probe = adapter.testCredential(apiKey, cfg.getBaseUrl());

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

    private ProviderInfoVO toInfoVO(ProviderEnum provider, LlmModelConfig cfg) {
        boolean configured = cfg != null && cfg.getApiKeyEncrypted() != null;
        return ProviderInfoVO.builder()
                .providerCode(provider.getCode())
                .providerName(provider.getDisplayName())
                .configured(configured)
                .status(configured ? (cfg.getStatus() == null ? "enabled" : cfg.getStatus()) : "not_configured")
                .apiKeyMasked(configured ? cfg.getApiKeyMasked() : null)
                .lastTestTime(cfg != null && cfg.getLastTestTime() != null ? cfg.getLastTestTime().format(TS) : null)
                .build();
    }

    private ProviderEnum requireProvider(String providerCode) {
        ProviderEnum provider = ProviderEnum.fromCode(providerCode);
        if (provider == null) {
            throw new IllegalArgumentException("不支持的模型厂家: " + providerCode);
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
