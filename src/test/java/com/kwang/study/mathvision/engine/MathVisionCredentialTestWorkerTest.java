package com.kwang.study.mathvision.engine;

import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.enums.ProviderEnum;
import com.kwang.study.mathvision.mapper.LlmModelConfigMapper;
import com.kwang.study.mathvision.pojo.LlmModelConfig;
import com.kwang.study.mathvision.provider.*;
import com.kwang.study.mathvision.util.ApiKeyCipher;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MathVisionCredentialTestWorkerTest {
    private final MathVisionCredentialTestStore store = mock(MathVisionCredentialTestStore.class);
    private final LlmModelConfigMapper configs = mock(LlmModelConfigMapper.class);
    private final ProviderAdapterRegistry registry = mock(ProviderAdapterRegistry.class);
    private final LlmProviderAdapter adapter = mock(LlmProviderAdapter.class);
    private final MathVisionModelCatalog catalog = new MathVisionModelCatalog();
    private final ApiKeyCipher cipher = new ApiKeyCipher("worker-test-secret");
    private final MathVisionCredentialTestWorker worker = new MathVisionCredentialTestWorker(store, configs, catalog, cipher, registry);
    private final String id = "a".repeat(32);
    private LlmModelConfig config;
    private MathVisionCredentialTestStore.Request request;

    @BeforeEach void setup() {
        config = LlmModelConfig.builder().id(3L).ownerUserId(7L).provider("custom_test").isCustom(true)
                .compatibilityType("openai").baseUrl("https://proxy.invalid/v1").modelName("private-model")
                .apiKeyEncrypted(cipher.encrypt("private-key")).status("invalid").build();
        request = new MathVisionCredentialTestStore.Request(); request.setTestId(id); request.setUserId(7L);
        request.setConfigId(3L); request.setProviderCode("custom_test"); request.setFingerprint("same-config");
        request.setDeadline(System.currentTimeMillis() + 25_000);
        when(store.find(id)).thenReturn(request); when(configs.findById(3L)).thenReturn(config);
        when(store.fingerprint(config)).thenReturn("same-config");
        when(registry.get(ProviderEnum.OPENAI)).thenReturn(adapter);
    }
    @AfterEach void stop() { worker.close(); }

    @Test void workerReadsKeyFromMysqlAndTestsTheCustomModelEvenIfPreviouslyMarkedInvalid() {
        when(adapter.testModel("private-key", "https://proxy.invalid/v1", "private-model"))
                .thenReturn(ProviderProbeResult.ok("passed"));
        worker.test(id);
        assertEquals("success", result().getState());
        verify(adapter, never()).testCredential(any(), any());
        verify(configs, never()).updateTestResult(any());
        assertFalse(MathVisionExecutionScope.active());
    }

    @Test void doesNotLeakKeyInProviderError() {
        when(adapter.testModel(any(), any(), any())).thenReturn(ProviderProbeResult.fail("HTTP 401: key=private-key"));
        worker.test(id);
        assertFalse(result().getMessage().contains("private-key"));
        assertEquals("failed", result().getState());
    }

    @Test void changedAndUnauthorizedConfigurationNeverCallsProvider() {
        request.setFingerprint("old-config"); worker.test(id);
        assertEquals("changed", result().getState());
        verifyNoInteractions(adapter);
    }

    @Test void workerTestsBuiltInProviderFromItsOwnConfiguredBaseUrl() {
        config.setIsCustom(false); config.setProvider("openai"); request.setProviderCode("openai");
        var provider = new MathVisionModelCatalog.ProviderCatalog(); provider.setCode("openai");
        provider.setEnabled(true); provider.setBaseUrl("https://official-proxy.invalid/v1");
        catalog.setModelProviders(java.util.List.of(provider));
        when(adapter.testCredential("private-key", "https://official-proxy.invalid/v1"))
                .thenReturn(ProviderProbeResult.ok("passed"));
        worker.test(id);
        assertEquals("success", result().getState());
        verify(adapter, never()).testModel(any(), any(), any());
    }

    @Test void expiredTestNeverCallsProvider() {
        request.setDeadline(0); worker.test(id);
        verifyNoInteractions(adapter); verify(store, never()).complete(any(), any());
    }

    private MathVisionCredentialTestStore.WorkerResult result() {
        var captor = ArgumentCaptor.forClass(MathVisionCredentialTestStore.WorkerResult.class);
        verify(store).complete(eq(id), captor.capture()); return captor.getValue();
    }
}
