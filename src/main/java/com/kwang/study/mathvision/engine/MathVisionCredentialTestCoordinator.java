package com.kwang.study.mathvision.engine;

import com.kwang.study.mathvision.dto.CredentialTestResultVO;
import com.kwang.study.mathvision.mapper.LlmModelConfigMapper;
import com.kwang.study.mathvision.pojo.LlmModelConfig;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;

/** The master only coordinates tests; it never connects to a model provider here. */
@Component
public class MathVisionCredentialTestCoordinator {
    private final MathVisionCredentialTestStore store;
    private final LlmModelConfigMapper configs;

    public MathVisionCredentialTestCoordinator(MathVisionCredentialTestStore store, LlmModelConfigMapper configs) {
        this.store = store; this.configs = configs;
    }

    public CredentialTestResultVO start(LlmModelConfig config) {
        try {
            var request = store.create(config);
            return response(config, request.getTestId(), "pending", false, "正在等待 Worker 测试，请稍候", List.of());
        } catch (org.springframework.dao.DataAccessException unavailable) {
            return response(config, null, "unavailable", false, "测试协调服务暂不可用，请稍后重试；凭据状态未修改", List.of());
        }
    }

    public CredentialTestResultVO status(Long userId, String provider, String testId) {
        LlmModelConfig config = configs.findByOwnerAndProvider(userId, provider);
        if (config == null) throw new IllegalArgumentException("模型配置不存在");
        try {
            var request = store.find(testId);
            if (request == null) return response(config, testId, "expired", false, "测试已过期，请重新测试；凭据状态未修改", List.of());
            if (!userId.equals(request.getUserId()) || !provider.equals(request.getProviderCode())
                    || !config.getId().equals(request.getConfigId())) throw new IllegalArgumentException("无权查看该测试");
            if (!request.getFingerprint().equals(store.fingerprint(config))) {
                store.stop(testId);
                return response(config, testId, "changed", false, "模型配置已变更，请重新测试；旧测试结果未应用", List.of());
            }
            var results = store.results(testId);
            long passed = results.stream().filter(result -> "success".equals(result.getState())).count();
            if (passed > 0) {
                LlmModelConfig success = new LlmModelConfig(); success.setId(config.getId());
                success.setStatus("enabled"); success.setLastTestTime(LocalDateTime.now());
                success.setLastTestResult("Worker 测试通过");
                if (!"disabled".equals(config.getStatus())) {
                    if (configs.updateTestResultIfUnchanged(success, config) == 0)
                        return response(config, testId, "changed", false, "模型配置已变更，请重新测试；旧测试结果未应用", results);
                    config.setStatus("enabled");
                }
                store.stop(testId);
                return response(config, testId, "completed", true,
                        "已有 " + passed + " 个 Worker 测试通过，可由能够访问该服务的 Worker 执行任务", results);
            }
            if (System.currentTimeMillis() < request.getDeadline())
                return response(config, testId, "pending", false, "正在等待 Worker 测试结果", results);
            store.stop(testId);
            return response(config, testId, "unavailable", false, results.isEmpty()
                    ? "未收到 Worker 响应，请确认 Worker 在线且共享同一 Redis；凭据状态未修改"
                    : "本轮尚无 Worker 测试成功，请查看各节点结果；网络、权限或服务异常不直接判定 API Key 无效，凭据状态未修改", results);
        } catch (org.springframework.dao.DataAccessException unavailable) {
            return response(config, testId, "unavailable", false, "测试协调服务暂不可用，请稍后重试；凭据状态未修改", List.of());
        }
    }

    private CredentialTestResultVO response(LlmModelConfig config, String id, String state, boolean success,
                                            String message, List<MathVisionCredentialTestStore.WorkerResult> results) {
        return CredentialTestResultVO.builder().providerCode(config.getProvider()).success(success).status(config.getStatus())
                .testId(id).testState(state).message(message).workerResults(results).build();
    }
}
