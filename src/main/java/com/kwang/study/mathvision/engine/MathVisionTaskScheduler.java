package com.kwang.study.mathvision.engine;

import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.mapper.MathVisionTaskMapper;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.service.MathVisionTaskNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Map;
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
    private final Map<String, Active> active = new ConcurrentHashMap<>();
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "mathvision-lease-monitor"); thread.setDaemon(true); return thread;
    });
    private volatile boolean stopping;
    private volatile long nextPoll;

    @org.springframework.beans.factory.annotation.Autowired
    public MathVisionTaskScheduler(MathVisionTaskMapper tasks, MathVisionStageRunner runner,
            MathVisionTaskNotifier notifier, MathVisionTaskExecutionRegistry registry,
            MathVisionModelCatalog catalog, @Qualifier("mathVisionTaskExecutor") Executor executor) {
        this(tasks, runner, notifier, registry, catalog, executor, new Random(), System::nanoTime);
    }

    MathVisionTaskScheduler(MathVisionTaskMapper tasks, MathVisionStageRunner runner,
            MathVisionTaskNotifier notifier, MathVisionTaskExecutionRegistry registry,
            MathVisionModelCatalog catalog, Executor executor, Random random, LongSupplier nanoTime) {
        this.tasks = tasks; this.runner = runner; this.notifier = notifier;
        this.registry = registry; this.catalog = catalog; this.executor = executor;
        this.polling = new MathVisionPollingPolicy(random);
        this.nanoTime = nanoTime;
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
            for (Long id : polling.shuffledCandidates(tasks.findClaimCandidates(Math.min(capacity, 16) * 2))) {
                if (active.size() >= Math.max(1, c.getConcurrency())) break;
                String token = UUID.randomUUID().toString();
                if (tasks.claimWithLease(id, token, Math.max(30, c.getLeaseSeconds()), c.getRecoveryLimit()) != 1) continue;
                MathVisionTask task = tasks.findById(id);
                if (task == null || !token.equals(task.getLockToken())) continue;
                Active run = new Active(task);
                active.put(token, run);
                log.info("MathVision task claimed, instance={}, taskId={}, version={}, stage={}, execution={}, recoveryCount={}",
                        INSTANCE, id, task.getCurrentVersion(), task.getCurrentStage(), token.substring(0, 8), task.getRecoveryCount());
                try {
                    executor.execute(() -> {
                        registry.register(id, token);
                        try (MathVisionExecutionScope ignored = new MathVisionExecutionScope()) {
                            catalog.getWorkflow(); catalog.getModelDefaults(); catalog.getModelProviders();
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
                    registry.interrupt(run.task.getId(), run.task.getLockToken()); continue;
                }
                int heartbeat = Math.max(1, Math.min(c.getHeartbeatSeconds(), Math.max(30, c.getLeaseSeconds()) / 3));
                if (now - run.renewed >= TimeUnit.SECONDS.toNanos(heartbeat)) {
                    if (tasks.renewLease(run.task.getId(), run.task.getLockToken(), Math.max(30, c.getLeaseSeconds())) != 1) {
                        run.fenced = true;
                        registry.interrupt(run.task.getId(), run.task.getLockToken());
                    }
                    run.renewed = now;
                }
            } catch (Exception e) {
                run.fenced = true;
                registry.interrupt(run.task.getId(), run.task.getLockToken());
                log.warn("MathVision lease monitor unavailable; stopping local execution, taskId={}", run.task.getId());
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
