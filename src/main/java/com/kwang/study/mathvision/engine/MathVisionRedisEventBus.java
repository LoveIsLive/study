package com.kwang.study.mathvision.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.mathvision.mapper.MathVisionTaskMapper;
import com.kwang.study.mathvision.service.MathVisionTaskNotifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

/** Delivery is best-effort. Ownership and cancellation remain persisted in MySQL. */
@Component
public class MathVisionRedisEventBus implements MessageListener {
    private final java.util.concurrent.ThreadPoolExecutor outbound = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(128), r -> {
                Thread thread = new Thread(r, "mathvision-event-publisher"); thread.setDaemon(true); return thread;
            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    public static final String CHANNEL = "mathvision:task-events";
    private final StringRedisTemplate redis;
    private final MathVisionTaskMapper tasks;
    private final MathVisionTaskExecutionRegistry registry;
    private final ObjectProvider<MathVisionTaskNotifier> notifier;
    private final ObjectMapper json;
    public MathVisionRedisEventBus(StringRedisTemplate redis, MathVisionTaskMapper tasks,
            MathVisionTaskExecutionRegistry registry, ObjectProvider<MathVisionTaskNotifier> notifier, ObjectMapper json) {
        this.redis = redis; this.tasks = tasks; this.registry = registry; this.notifier = notifier; this.json = json;
    }
    public void publish(Long id, String event) {
        try { outbound.execute(() -> send(id, event)); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }
    private void send(Long id, String event) {
        try {
            var task = tasks.findById(id); if (task == null) return;
            var body = json.createObjectNode().put("taskId", id).put("event", event).put("token", task.getLockToken());
            redis.convertAndSend(CHANNEL, body.toString());
        } catch (Exception ignored) { /* DB polling provides recovery without a second durable queue. */ }
    }
    @javax.annotation.PreDestroy public void stop() { outbound.shutdownNow(); }
    @Override public void onMessage(Message message, byte[] pattern) {
        try {
            var body = json.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            long id = body.path("taskId").asLong(); String event = body.path("event").asText();
            if ("cancel_requested".equals(event)) {
                var current = tasks.findById(id);
                if (current != null && Boolean.TRUE.equals(current.getCancelRequested())
                        && body.path("token").asText().equals(current.getLockToken())) registry.interrupt(id, current.getLockToken());
            }
            MathVisionTaskNotifier receiver = notifier.getIfAvailable();
            if (receiver != null) receiver.deliverRemote(id, event);
        } catch (Exception ignored) { }
    }
}
