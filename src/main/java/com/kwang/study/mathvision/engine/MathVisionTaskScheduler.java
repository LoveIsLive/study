package com.kwang.study.mathvision.engine;

import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.mapper.MathVisionTaskMapper;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.service.MathVisionTaskNotifier;
import com.kwang.study.mathvision.workflow.ai.MathVisionAiChatService;
import com.kwang.study.mathvision.workflow.ai.MathVisionModelReachability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Database leases are authoritative; Redis is only an acceleration channel. */
@Component
@ConditionalOnProperty(name = "mathvision.worker.enabled", havingValue = "true")
public class MathVisionTaskScheduler {
    private static final Logger log = LoggerFactory.getLogger(MathVisionTaskScheduler.class);
    private static final String INSTANCE = System.getenv().getOrDefault("MATHVISION_WORKER_ID",
            System.getenv().getOrDefault("HOSTNAME", "worker-" + UUID.randomUUID().toString().substring(0, 8)));
    private final MathVisionTaskMapper tasks;
    private final MathVisionStageRunner runner;
    private final MathVisionTaskNotifier notifier;
    private final MathVisionTaskExecutionRegistry registry;
    private final MathVisionModelCatalog catalog;
    private final Executor executor;
    private final MathVisionPollingPolicy polling;
    private final LongSupplier nanoTime;
    private final MathVisionAiChatService ai;
    private final MathVisionModelReachability reachability;
    private java.time.LocalDateTime scanTime;
    private Long scanId;
    private final Map<Long, Long> skippedLogTimes = new LinkedHashMap<>();
    private final Map<String, Active> active = new ConcurrentHashMap<>();
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "mathvision-lease-monitor"); thread.setDaemon(true); return thread;
    });
    private volatile boolean stopping;
    private volatile long nextPoll;

    @org.springframework.beans.factory.annotation.Autowired
    public MathVisionTaskScheduler(MathVisionTaskMapper tasks, MathVisionStageRunner runner,
            MathVisionTaskNotifier notifier, MathVisionTaskExecutionRegistry registry,
            MathVisionModelCatalog catalog, @Qualifier("mathVisionTaskExecutor") Executor executor,
            MathVisionAiChatService ai, MathVisionModelReachability reachability) {
        this(tasks, runner, notifier, registry, catalog, executor, new Random(), System::nanoTime, ai, reachability);
    }

    MathVisionTaskScheduler(MathVisionTaskMapper tasks, MathVisionStageRunner runner,
            MathVisionTaskNotifier notifier, MathVisionTaskExecutionRegistry registry,
            MathVisionModelCatalog catalog, Executor executor, Random random, LongSupplier nanoTime,
            MathVisionAiChatService ai, MathVisionModelReachability reachability) {
        this.tasks = tasks; this.runner = runner; this.notifier = notifier;
        this.registry = registry; this.catalog = catalog; this.executor = executor;
        this.polling = new MathVisionPollingPolicy(random);
        this.nanoTime = nanoTime;
        this.ai = ai; this.reachability = reachability;
    }

    @PostConstruct public synchronized void start() {
        MathVisionModelCatalog.DistributedCatalog c = catalog.getDistributed();
        nextPoll = nanoTime.getAsLong() + TimeUnit.MILLISECONDS.toNanos(
                polling.initialDelayMillis(c.getPollMinMillis(), c.getPollMaxMillis()));
        monitor.scheduleWithFixedDelay(this::monitor, 200, 200, TimeUnit.MILLISECONDS);
    }

    @Scheduled(fixedDelay = 250, initialDelay = 1000)
    public synchronized void pollQueuedTasks() {
        if (stopping || nanoTime.getAsLong() - nextPoll < 0) return;
        MathVisionModelCatalog.DistributedCatalog c = catalog.getDistributed();
        int capacity = Math.max(1, c.getConcurrency()) - active.size();
        if (capacity <= 0) return;
        boolean found = false;
        try {
            tasks.settleExpiredLeases(c.getRecoveryLimit());
            int batchSize = Math.min(capacity, 16) * 2;
            List<MathVisionTask> candidates = tasks.findModelClaimCandidates(scanTime, scanId, batchSize, c.getRecoveryLimit());
            if (candidates.isEmpty() && scanTime != null) {
                scanTime = null; scanId = null;
                candidates = tasks.findModelClaimCandidates(null, null, batchSize, c.getRecoveryLimit());
            }
            if (!candidates.isEmpty()) {
                MathVisionTask tail = candidates.get(candidates.size() - 1);
                scanTime = tail.getUpdateTime(); scanId = tail.getId();
            }
            for (MathVisionTask candidate : polling.shuffledCandidates(candidates)) {
                if (active.size() >= Math.max(1, c.getConcurrency())) break;
                Long id = candidate.getId();
                MathVisionAiChatService.PreparedExecution prepared;
                try {
                    prepared = ai.prepareExecution(candidate);
                } catch (IllegalArgumentException invalidConfig) {
                    logSkipped(candidate, "模型连接配置无效：" + invalidConfig.getClass().getSimpleName());
                    continue;
                }
                if (!reachability.canAccess(prepared.getEndpoint(), id)) {
                    logSkipped(candidate, "模型服务连通性未通过或正在检测");
                    continue;
                }
                String token = UUID.randomUUID().toString();
                if (tasks.claimWithModelLease(candidate, token, Math.max(30, c.getLeaseSeconds()), c.getRecoveryLimit()) != 1) continue;
                MathVisionTask task = tasks.findById(id);
                if (task == null || !token.equals(task.getLockToken())) continue;
                // Settings changed after the successful CAS belong to the next stage. Execute
                // the exact model configuration whose endpoint was checked before this claim.
                task.setSelectedModelConfigId(candidate.getSelectedModelConfigId());
                task.setProviderCode(candidate.getProviderCode()); task.setModelName(candidate.getModelName());
                Active run = new Active(task);
                active.put(token, run);
                log.info("MathVision task claimed, instance={}, taskId={}, version={}, stage={}, execution={}, recoveryCount={}",
                        INSTANCE, id, task.getCurrentVersion(), task.getCurrentStage(), token.substring(0, 8), task.getRecoveryCount());
                try {
                    executor.execute(() -> {
                        registry.register(id, token);
                        try (MathVisionExecutionScope ignored = prepared.openScope()) {
                            runner.runOneVisibleStage(task);
                        } finally {
                            registry.unregister(id, token); active.remove(token, run); Thread.interrupted();
                        }
                    });
                    found = true; notifier.notifyTaskChanged(id, "running");
                } catch (RejectedExecutionException e) {
                    active.remove(token, run); tasks.releaseUnstartedLease(id, token);
                }
            }
        } finally {
            // Schedule even if MySQL failed, so the scheduler tick cannot become a retry storm.
            nextPoll = nanoTime.getAsLong() + TimeUnit.MILLISECONDS.toNanos(
                    polling.nextDelayMillis(found, c.getPollMinMillis(), c.getPollMaxMillis()));
        }
    }

    private void logSkipped(MathVisionTask task, String reason) {
        long now = nanoTime.getAsLong();
        Long previous = skippedLogTimes.get(task.getId());
        if (previous != null && now - previous < TimeUnit.MINUTES.toNanos(1)) return;
        if (skippedLogTimes.size() >= 1024) skippedLogTimes.remove(skippedLogTimes.keySet().iterator().next());
        skippedLogTimes.put(task.getId(), now);
        log.info("MathVision Worker 暂不领取任务, instance={}, taskId={}, stage={}, provider={}, reason={}",
                INSTANCE, task.getId(), task.getCurrentStage(), task.getProviderCode(), reason);
    }

    private void monitor() {
        long now = System.nanoTime();
        MathVisionModelCatalog.DistributedCatalog c = catalog.getDistributed();
        for (Active run : active.values()) {
            try {
                if (run.fenced) continue;
                if (now - run.checked < TimeUnit.MILLISECONDS.toNanos(Math.max(200, c.getCancelPollMillis()))) continue;
                run.checked = now;
                MathVisionTask current = tasks.findById(run.task.getId());
                if (current == null || !run.task.getLockToken().equals(current.getLockToken())
                        || !"running".equals(current.getStatus()) || Boolean.TRUE.equals(current.getCancelRequested())) {
                    run.fenced = true;
                    log.info("MathVision 停止本地执行, taskId={}, stage={}, execution={}, reason={}",
                            run.task.getId(), run.task.getCurrentStage(), run.task.getLockToken().substring(0, 8),
                            current != null && Boolean.TRUE.equals(current.getCancelRequested()) ? "用户取消" : "任务状态或执行权已变化");
                    registry.interrupt(run.task.getId(), run.task.getLockToken()); continue;
                }
                int heartbeat = Math.max(1, Math.min(c.getHeartbeatSeconds(), Math.max(30, c.getLeaseSeconds()) / 3));
                if (now - run.renewed >= TimeUnit.SECONDS.toNanos(heartbeat)) {
                    if (tasks.renewLease(run.task.getId(), run.task.getLockToken(), Math.max(30, c.getLeaseSeconds())) != 1) {
                        run.fenced = true;
                        log.warn("MathVision 续租失败，停止本地执行, taskId={}, stage={}, execution={}",
                                run.task.getId(), run.task.getCurrentStage(), run.task.getLockToken().substring(0, 8));
                        registry.interrupt(run.task.getId(), run.task.getLockToken());
                    }
                    run.renewed = now;
                }
            } catch (Exception e) {
                run.fenced = true;
                registry.interrupt(run.task.getId(), run.task.getLockToken());
                log.warn("MathVision lease monitor unavailable; stopping local execution, taskId={}, stage={}, errorType={}, reason={}",
                        run.task.getId(), run.task.getCurrentStage(), e.getClass().getSimpleName(),
                        com.kwang.study.mathvision.workflow.util.NodeExecutionLogger.summarize(e.getMessage()));
            }
        }
    }

    @PreDestroy public void stop() {
        stopping = true; monitor.shutdownNow();
        active.values().forEach(run -> registry.interrupt(run.task.getId(), run.task.getLockToken()));
    }

    private static final class Active {
        final MathVisionTask task;
        long checked;
        volatile boolean fenced;
        long renewed = System.nanoTime();
        Active(MathVisionTask task) { this.task = task; }
    }
}
