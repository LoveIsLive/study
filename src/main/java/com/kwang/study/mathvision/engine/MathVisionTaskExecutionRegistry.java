package com.kwang.study.mathvision.engine;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Collections;
import java.util.Set;

/** Tracks active task workers so a persisted cancellation request can wake them immediately. */
@Component
public class MathVisionTaskExecutionRegistry {

    private final ConcurrentMap<String, Execution> activeExecutions = new ConcurrentHashMap<>();

    private static String key(Long taskId, String token) { return taskId + ":" + token; }

    public void register(Long taskId) { register(taskId, null); }
    public void register(Long taskId, String token) {
        if (taskId != null) {
            activeExecutions.put(key(taskId, token), new Execution(Thread.currentThread()));
        }
    }

    public void unregister(Long taskId) { unregister(taskId, null); }
    public void unregister(Long taskId, String token) {
        if (taskId != null) {
            Execution execution = activeExecutions.get(key(taskId, token));
            if (execution != null && execution.thread == Thread.currentThread()) {
                activeExecutions.remove(key(taskId, token), execution);
            }
        }
    }

    public void registerCancellationHook(Long taskId, Runnable hook) { registerCancellationHook(taskId, null, hook); }
    public void registerCancellationHook(Long taskId, String token, Runnable hook) {
        if (taskId == null || hook == null) {
            return;
        }
        Execution execution = activeExecutions.get(key(taskId, token));
        if (execution == null) {
            return;
        }
        execution.hooks.add(hook);
        if (execution.canceled) {
            invokeHook(hook);
        }
    }

    public void unregisterCancellationHook(Long taskId, Runnable hook) { unregisterCancellationHook(taskId, null, hook); }
    public void unregisterCancellationHook(Long taskId, String token, Runnable hook) {
        Execution execution = taskId != null ? activeExecutions.get(key(taskId, token)) : null;
        if (execution != null && hook != null) {
            execution.hooks.remove(hook);
        }
    }

    public boolean interrupt(Long taskId) { return interrupt(taskId, null); }
    public boolean interrupt(Long taskId, String token) {
        Execution execution = taskId != null ? activeExecutions.get(key(taskId, token)) : null;
        if (execution == null) {
            return false;
        }
        synchronized (execution) {
            if (execution.canceled) return true;
            execution.canceled = true;
        }
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
