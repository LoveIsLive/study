package com.kwang.study.mathvision.service;

import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.dto.CustomProviderConfigDTO;
import com.kwang.study.mathvision.mapper.LlmModelConfigMapper;
import com.kwang.study.mathvision.pojo.LlmModelConfig;
import com.kwang.study.mathvision.provider.ProviderAdapterRegistry;
import com.kwang.study.mathvision.util.ApiKeyCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmModelConfigServiceTest {

    private LlmModelConfigMapper mapper;
    private LlmModelConfigService service;

    @BeforeEach
    void setUp() {
        mapper = mock(LlmModelConfigMapper.class);
        service = new LlmModelConfigService(
                mapper,
                new ApiKeyCipher("custom-provider-test-secret"),
                mock(ProviderAdapterRegistry.class),
                new MathVisionModelCatalog());
    }

    @Test
    void createsCustomProviderInExistingCredentialTable() {
        CustomProviderConfigDTO request = request();

        service.createCustomProvider(7L, request);

        ArgumentCaptor<LlmModelConfig> captor = ArgumentCaptor.forClass(LlmModelConfig.class);
        verify(mapper).insert(captor.capture());
        LlmModelConfig saved = captor.getValue();
        assertTrue(Boolean.TRUE.equals(saved.getIsCustom()));
        assertTrue(saved.getProvider().startsWith("custom_"));
        assertEquals("openai", saved.getCompatibilityType());
        assertEquals("https://api.vendor.example/v1", saved.getBaseUrl());
        assertEquals("vendor-model", saved.getModelName());
        assertEquals("enabled", saved.getStatus());
    }

    @Test
    void exposesConfiguredCustomProviderAndItsSingleDeclaredModel() {
        LlmModelConfig config = LlmModelConfig.builder()
                .ownerUserId(7L)
                .provider("custom_1")
                .providerName("测试厂家")
                .isCustom(true)
                .compatibilityType("anthropic")
                .baseUrl("https://api.vendor.example/v1")
                .modelName("vendor-model")
                .supportVision(true)
                .contextWindow(128_000)
                .maxOutputTokens(16_384)
                .apiKeyEncrypted("encrypted")
                .apiKeyMasked("****test")
                .status("enabled")
                .build();
        when(mapper.findCustomByOwner(7L)).thenReturn(List.of(config));
        when(mapper.findByOwnerAndProvider(7L, "custom_1")).thenReturn(config);

        assertEquals("测试厂家", service.listProviders(7L).get(0).getProviderName());
        assertEquals("vendor-model", service.listModels(7L, "custom_1")
                .getModels().get(0).getModelName());
        assertTrue(service.listModels(7L, "custom_1").getModels().get(0).getSupportVision());
    }

    @Test
    void rejectsPrivateCustomBaseUrl() {
        CustomProviderConfigDTO request = request();
        request.setBaseUrl("http://127.0.0.1:11434/v1");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.createCustomProvider(7L, request));

        assertTrue(error.getMessage().contains("内网地址"));
    }

    private CustomProviderConfigDTO request() {
        CustomProviderConfigDTO request = new CustomProviderConfigDTO();
        request.setProviderName("测试厂家");
        request.setCompatibilityType("openai");
        request.setBaseUrl("https://api.vendor.example/v1/");
        request.setApiKey("secret-key");
        request.setModelName("vendor-model");
        request.setSupportVision(true);
        request.setContextWindow(128_000);
        request.setMaxOutputTokens(16_384);
        return request;
    }
}
