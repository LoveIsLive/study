package com.kwang.study.mathvision.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.pojo.LlmModelConfig;
import com.kwang.study.mathvision.util.MathVisionContentTag;
import lombok.Data;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/** Disposable UI tests use expiring Redis records, independently of the MySQL task queue. */
@Component
public class MathVisionCredentialTestStore {
    private static final String PREFIX = "mathvision:credential-test:";
    private static final String INDEX = PREFIX + "active";
    private static final Duration RETENTION = Duration.ofSeconds(90);
    public static final long TEST_MILLIS = 25_000;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final MathVisionModelCatalog catalog;

    public MathVisionCredentialTestStore(StringRedisTemplate redis, ObjectMapper json, MathVisionModelCatalog catalog) {
        this.redis = redis; this.json = json; this.catalog = catalog;
    }

    public Request create(LlmModelConfig config) {
        Request request = new Request();
        request.setTestId(UUID.randomUUID().toString().replace("-", ""));
        request.setUserId(config.getOwnerUserId()); request.setConfigId(config.getId());
        request.setProviderCode(config.getProvider()); request.setFingerprint(fingerprint(config));
        request.setDeadline(System.currentTimeMillis() + TEST_MILLIS);
        redis.opsForValue().set(key(request.getTestId()), write(request), RETENTION);
        redis.opsForZSet().add(INDEX, request.getTestId(), request.getDeadline());
        redis.expire(INDEX, RETENTION.plusSeconds(30));
        return request;
    }

    public Request find(String id) {
        validateId(id);
        String value = redis.opsForValue().get(key(id));
        return value == null ? null : read(value, Request.class);
    }

    public List<String> active(int offset) {
        redis.opsForZSet().removeRangeByScore(INDEX, 0, System.currentTimeMillis());
        Set<String> ids = redis.opsForZSet().rangeByScore(INDEX, System.currentTimeMillis(), Double.POSITIVE_INFINITY, offset, 32);
        return ids == null ? List.of() : new ArrayList<>(ids);
    }

    public boolean begin(String id, String worker) {
        WorkerResult running = new WorkerResult(); running.setWorker(worker); running.setState("testing");
        Boolean first = redis.opsForHash().putIfAbsent(key(id) + ":results", worker, write(running));
        redis.expire(key(id) + ":results", RETENTION);
        return Boolean.TRUE.equals(first);
    }

    public void complete(String id, WorkerResult result) {
        redis.opsForHash().put(key(id) + ":results", result.getWorker(), write(result));
        redis.expire(key(id) + ":results", RETENTION);
    }

    public List<WorkerResult> results(String id) {
        List<WorkerResult> results = new ArrayList<>();
        for (Object value : redis.opsForHash().values(key(id) + ":results"))
            results.add(read(value.toString(), WorkerResult.class));
        results.sort(Comparator.comparing(WorkerResult::getWorker));
        return results;
    }

    public void stop(String id) { redis.opsForZSet().remove(INDEX, id); }

    /** Never put the key itself, encrypted or clear, in Redis or in test responses. */
    public String fingerprint(LlmModelConfig config) {
        return MathVisionContentTag.of(write(Arrays.asList(config.getId(), config.getOwnerUserId(), config.getProvider(),
                config.getIsCustom(), config.getCompatibilityType(), config.getBaseUrl(), config.getModelName(),
                config.getApiKeyEncrypted(), config.getExtraHeadersJson(),
                Boolean.TRUE.equals(config.getIsCustom()) ? null : catalog.findEnabled(config.getProvider()))));
    }

    private static String key(String id) { validateId(id); return PREFIX + id; }
    private static void validateId(String id) {
        if (id == null || !id.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("无效的测试编号");
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Could not serialize credential test", e); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception e) { throw new IllegalStateException("Could not read credential test", e); }
    }

    @Data public static class Request {
        private String testId, providerCode, fingerprint;
        private Long configId, userId;
        private long deadline;
    }
    @Data public static class WorkerResult {
        private String worker, state, message;
        private long durationMillis;
    }
}
