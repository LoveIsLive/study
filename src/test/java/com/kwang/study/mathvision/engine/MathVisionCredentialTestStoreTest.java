package com.kwang.study.mathvision.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.pojo.LlmModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MathVisionCredentialTestStoreTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") private final ValueOperations<String, String> values = mock(ValueOperations.class);
    @SuppressWarnings("unchecked") private final ZSetOperations<String, String> index = mock(ZSetOperations.class);
    @SuppressWarnings("unchecked") private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    private final MathVisionModelCatalog catalog = new MathVisionModelCatalog();
    private final MathVisionCredentialTestStore store = new MathVisionCredentialTestStore(redis, new ObjectMapper(), catalog);
    private final LlmModelConfig config = LlmModelConfig.builder().id(3L).ownerUserId(7L).provider("custom_test")
            .isCustom(true).compatibilityType("openai").baseUrl("https://proxy.invalid/v1").modelName("model")
            .apiKeyEncrypted("private-encrypted-key").status("enabled").build();
    @BeforeEach void setup() {
        when(redis.opsForValue()).thenReturn(values); when(redis.opsForZSet()).thenReturn(index);
        when(redis.opsForHash()).thenReturn(hashes);
    }

    @Test void requestContainsNoCredentialAndHasBoundedLifetime() {
        var request = store.create(config);
        var body = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("mathvision:credential-test:" + request.getTestId()), body.capture(), eq(Duration.ofSeconds(90)));
        assertFalse(body.getValue().contains("private-encrypted-key"));
        assertFalse(body.getValue().contains("https://proxy"));
        assertTrue(body.getValue().contains("\"userId\":7"));
        assertTrue(request.getDeadline() - System.currentTimeMillis() <= 25_000);
        when(values.get(anyString())).thenReturn(body.getValue());
        assertEquals(request.getFingerprint(), store.find(request.getTestId()).getFingerprint());
        verify(redis).expire("mathvision:credential-test:active", Duration.ofSeconds(120));
    }

    @Test void resultsAreSeparateForEachWorkerAndCannotExecuteTwice() {
        String id = "a".repeat(32);
        when(hashes.putIfAbsent(anyString(), eq("one"), any())).thenReturn(true, false);
        assertTrue(store.begin(id, "one")); assertFalse(store.begin(id, "one"));
        verify(redis, times(2)).expire("mathvision:credential-test:" + id + ":results", Duration.ofSeconds(90));
    }

    @Test void fingerprintChangesWithCredentialsAndEndpointButNotPreviousTestStatus() {
        String baseline = store.fingerprint(config);
        config.setStatus("invalid"); config.setLastTestResult("network failed");
        assertEquals(baseline, store.fingerprint(config));
        config.setApiKeyEncrypted("new-encrypted-key"); assertNotEquals(baseline, store.fingerprint(config));
        config.setApiKeyEncrypted("private-encrypted-key"); config.setBaseUrl("https://other.invalid/v1");
        assertNotEquals(baseline, store.fingerprint(config));
        assertThrows(IllegalArgumentException.class, () -> store.find("../arbitrary-key"));
    }
}
