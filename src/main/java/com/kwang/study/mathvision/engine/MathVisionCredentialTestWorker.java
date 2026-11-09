package com.kwang.study.mathvision.engine;

import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.enums.ProviderEnum;
import com.kwang.study.mathvision.mapper.LlmModelConfigMapper;
import com.kwang.study.mathvision.provider.ProviderAdapterRegistry;
import com.kwang.study.mathvision.util.ApiKeyCipher;
import com.kwang.study.mathvision.workflow.ai.MathVisionModelEndpoint;
import com.kwang.study.mathvision.workflow.util.NodeExecutionLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.*;

/** Workers test from their own network. Busy generation slots do not block this small pool. */
@Component
@ConditionalOnProperty(name = "mathvision.worker.enabled", havingValue = "true")
public class MathVisionCredentialTestWorker {
    private static final Logger log = LoggerFactory.getLogger(MathVisionCredentialTestWorker.class);
    private final MathVisionCredentialTestStore store;
    private final LlmModelConfigMapper configs;
    private final MathVisionModelCatalog catalog;
    private final ApiKeyCipher cipher;
    private final ProviderAdapterRegistry adapters;
    private final ExecutorService executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), action -> { Thread t = new Thread(action, "mathvision-credential-test"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
    private final String worker = System.getenv().getOrDefault("MATHVISION_WORKER_ID", System.getenv().getOrDefault("HOSTNAME", "worker"))
            + "-" + UUID.randomUUID().toString().substring(0, 8);
    private int offset;

    public MathVisionCredentialTestWorker(MathVisionCredentialTestStore store, LlmModelConfigMapper configs,
            MathVisionModelCatalog catalog, ApiKeyCipher cipher, ProviderAdapterRegistry adapters) {
        this.store = store; this.configs = configs; this.catalog = catalog; this.cipher = cipher; this.adapters = adapters;
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void poll() {
        try {
            var ids = store.active(offset); offset = ids.size() < 32 ? 0 : offset + 32;
            for (String id : ids) {
                if (store.begin(id, worker)) {
                    try { executor.execute(() -> test(id)); }
                    catch (RejectedExecutionException busy) { finish(id, "busy", "Worker 测试繁忙，请重试", 0); }
                }
            }
        } catch (org.springframework.dao.DataAccessException unavailable) {
            // UI tests are disposable. Generation still uses MySQL independently of Redis.
        }
    }

    void test(String id) {
        long started = System.nanoTime();
        String apiKey = null;
        try (var scope = new MathVisionExecutionScope()) {
            var request = store.find(id);
            if (request == null || System.currentTimeMillis() >= request.getDeadline()) return;
            var config = configs.findById(request.getConfigId());
            if (config == null || !request.getUserId().equals(config.getOwnerUserId())
                    || !request.getProviderCode().equals(config.getProvider())
                    || !request.getFingerprint().equals(store.fingerprint(config))) {
                finish(id, "changed", "模型配置已变更，未执行旧测试", elapsed(started)); return;
            }
            boolean custom = Boolean.TRUE.equals(config.getIsCustom());
            var provider = custom ? null : catalog.findEnabled(config.getProvider());
            var adapter = adapters.get(ProviderEnum.fromCode(custom ? config.getCompatibilityType() : config.getProvider()));
            if (adapter == null || (!custom && provider == null)) {
                finish(id, "failed", "当前 Worker 未配置该厂家测试能力", elapsed(started)); return;
            }
            apiKey = cipher.decrypt(config.getApiKeyEncrypted());
            String baseUrl = custom ? config.getBaseUrl() : MathVisionModelEndpoint.baseUrl(provider.getCode(), provider.getBaseUrl());
            var result = custom ? adapter.testModel(apiKey, baseUrl, config.getModelName()) : adapter.testCredential(apiKey, baseUrl);
            String message = result.isSuccess() ? "API Key 测试通过" : result.getMessage();
            if (apiKey != null && !apiKey.isBlank() && message != null) {
                message = message.replace(apiKey, "<redacted>").replace(
                        java.net.URLEncoder.encode(apiKey, java.nio.charset.StandardCharsets.UTF_8), "<redacted>");
            }
            finish(id, result.isSuccess() ? "success" : "failed", NodeExecutionLogger.summarize(message), elapsed(started));
        } catch (Exception failure) {
            // Exception messages can contain credential-bearing URLs. Do not expose them.
            finish(id, "failed", "Worker 测试异常：" + failure.getClass().getSimpleName(), elapsed(started));
        }
    }

    private long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000L; }
    private void finish(String id, String state, String message, long duration) {
        var result = new MathVisionCredentialTestStore.WorkerResult();
        result.setWorker(worker); result.setState(state); result.setMessage(message); result.setDurationMillis(duration);
        try { store.complete(id, result); }
        catch (org.springframework.dao.DataAccessException ignored) { }
        log.info("MathVision Worker 模型测试结束, testId={}, worker={}, state={}, durationMs={}", id, worker, state, duration);
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
