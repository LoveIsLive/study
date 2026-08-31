package com.kwang.study.mathvision.engine;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Collections;
import java.util.Set;

/** Tracks active task workers so a persisted cancellation request can wake them immediately. */
@Component
public class MathVisionTaskExecutionRegistry {

    private final ConcurrentMap<Long, Execution> activeExecutions = new ConcurrentHashMap<>();

    public void register(Long taskId) {
        if (taskId != null) {
            activeExecutions.put(taskId, new Execution(Thread.currentThread()));
        }
    }

    public void unregister(Long taskId) {
        if (taskId != null) {
            Execution execution = activeExecutions.get(taskId);
            if (execution != null && execution.thread == Thread.currentThread()) {
                activeExecutions.remove(taskId, execution);
            }
        }
    }

    public void registerCancellationHook(Long taskId, Runnable hook) {
        if (taskId == null || hook == null) {
            return;
        }
        Execution execution = activeExecutions.get(taskId);
        if (execution == null) {
            return;
        }
        execution.hooks.add(hook);
        if (execution.canceled) {
            invokeHook(hook);
        }
    }

    public void unregisterCancellationHook(Long taskId, Runnable hook) {
        Execution execution = taskId != null ? activeExecutions.get(taskId) : null;
        if (execution != null && hook != null) {
            execution.hooks.remove(hook);
        }
    }

    public boolean interrupt(Long taskId) {
        Execution execution = taskId != null ? activeExecutions.get(taskId) : null;
        if (execution == null) {
            return false;
        }
        execution.canceled = true;
        execution.thread.interrupt();
        for (Runnable hook : execution.hooks) {
            invokeHook(hook);
        }
        return true;
    }

    private void invokeHook(Runnable hook) {
        Thread cleanupThread = new Thread(() -> {
            try {
                hook.run();
            } catch (RuntimeException ignored) {
                // Cancellation must not fail because a best-effort resource close failed.
            }
        }, "mathvision-cancel-cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    private static final class Execution {
        private final Thread thread;
        private final Set<Runnable> hooks = Collections.newSetFromMap(new ConcurrentHashMap<>());
        private volatile boolean canceled;

        private Execution(Thread thread) {
            this.thread = thread;
        }
    }
}
