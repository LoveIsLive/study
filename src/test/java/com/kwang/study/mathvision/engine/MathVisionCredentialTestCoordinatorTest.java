package com.kwang.study.mathvision.engine;

import com.kwang.study.mathvision.mapper.LlmModelConfigMapper;
import com.kwang.study.mathvision.pojo.LlmModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MathVisionCredentialTestCoordinatorTest {
    private final MathVisionCredentialTestStore store = mock(MathVisionCredentialTestStore.class);
    private final LlmModelConfigMapper configs = mock(LlmModelConfigMapper.class);
    private final MathVisionCredentialTestCoordinator coordinator = new MathVisionCredentialTestCoordinator(store, configs);
    private final LlmModelConfig config = LlmModelConfig.builder().id(3L).ownerUserId(7L).provider("custom_test")
            .status("enabled").apiKeyEncrypted("encrypted-key").build();
    private final MathVisionCredentialTestStore.Request request = new MathVisionCredentialTestStore.Request();

    @BeforeEach void setup() {
        request.setTestId("a".repeat(32)); request.setUserId(7L); request.setConfigId(3L);
        request.setProviderCode("custom_test"); request.setFingerprint("same-config");
        request.setDeadline(System.currentTimeMillis() + 25_000);
        when(configs.findByOwnerAndProvider(7L, "custom_test")).thenReturn(config);
        when(store.find(request.getTestId())).thenReturn(request);
        when(store.fingerprint(config)).thenReturn("same-config");
    }

    @Test void anySuccessfulWorkerWinsDespiteFailuresOnOtherNodes() {
        when(store.results(request.getTestId())).thenReturn(List.of(result("mainland", "failed"), result("overseas", "success")));
        when(configs.updateTestResultIfUnchanged(any(), eq(config))).thenReturn(1);
        var response = coordinator.status(7L, "custom_test", request.getTestId());
        assertTrue(response.getSuccess()); assertEquals("completed", response.getTestState());
        assertEquals(2, response.getWorkerResults().size()); assertEquals("enabled", response.getStatus());
        verify(configs).updateTestResultIfUnchanged(argThat(value -> "enabled".equals(value.getStatus())), eq(config));
    }

    @Test void doesNotFailEarlyWhenOnlyTheUnreachableWorkerHasResponded() {
        when(store.results(request.getTestId())).thenReturn(List.of(result("mainland", "failed")));
        assertEquals("pending", coordinator.status(7L, "custom_test", request.getTestId()).getTestState());
        verify(configs, never()).updateTestResultIfUnchanged(any(), any());
    }

    @Test void allFailuresAndNoWorkerKeepCredentialEnabled() {
        request.setDeadline(0);
        when(store.results(request.getTestId())).thenReturn(List.of(result("worker", "failed")), List.of());
        var failed = coordinator.status(7L, "custom_test", request.getTestId());
        assertEquals("unavailable", failed.getTestState()); assertEquals("enabled", failed.getStatus());
        assertTrue(failed.getMessage().contains("凭据状态未修改"));
        var empty = coordinator.status(7L, "custom_test", request.getTestId());
        assertTrue(empty.getMessage().contains("未收到 Worker"));
        verify(configs, never()).updateTestResultIfUnchanged(any(), any());
        verify(configs, never()).updateTestResult(any());
    }

    @Test void successCanRestoreALegacyInvalidCredential() {
        config.setStatus("invalid");
        when(store.results(request.getTestId())).thenReturn(List.of(result("worker", "success")));
        when(configs.updateTestResultIfUnchanged(any(), eq(config))).thenReturn(1);
        assertEquals("enabled", coordinator.status(7L, "custom_test", request.getTestId()).getStatus());
    }

    @Test void changedCredentialOrLostUpdateRejectsStaleSuccess() {
        when(store.fingerprint(config)).thenReturn("different-config", "same-config");
        assertEquals("changed", coordinator.status(7L, "custom_test", request.getTestId()).getTestState());
        verify(configs, never()).updateTestResultIfUnchanged(any(), any());
        when(store.results(request.getTestId())).thenReturn(List.of(result("worker", "success")));
        when(configs.updateTestResultIfUnchanged(any(), eq(config))).thenReturn(0);
        assertEquals("changed", coordinator.status(7L, "custom_test", request.getTestId()).getTestState());
    }

    @Test void cannotReadAnotherUsersResult() {
        request.setUserId(8L);
        assertThrows(IllegalArgumentException.class, () -> coordinator.status(7L, "custom_test", request.getTestId()));
    }

    @Test void redisFailureIsReportedWithoutLocalProviderFallback() {
        when(store.create(config)).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline"));
        var response = coordinator.start(config);
        assertEquals("unavailable", response.getTestState()); assertEquals("enabled", response.getStatus());
        verifyNoMoreInteractions(configs);
    }

    @Test void disabledCredentialIsNotEnabledBySuccessfulTest() {
        config.setStatus("disabled");
        when(store.results(request.getTestId())).thenReturn(List.of(result("worker", "success")));
        assertEquals("disabled", coordinator.status(7L, "custom_test", request.getTestId()).getStatus());
        verify(configs, never()).updateTestResultIfUnchanged(any(), any());
    }

    private MathVisionCredentialTestStore.WorkerResult result(String worker, String state) {
        var result = new MathVisionCredentialTestStore.WorkerResult(); result.setWorker(worker); result.setState(state); return result;
    }
}
