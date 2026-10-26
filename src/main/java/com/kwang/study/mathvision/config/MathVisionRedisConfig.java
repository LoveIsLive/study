package com.kwang.study.mathvision.config;

import com.kwang.study.mathvision.engine.MathVisionRedisEventBus;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.ChannelTopic;

@Configuration
public class MathVisionRedisConfig {
    private final java.util.concurrent.ScheduledExecutorService reconnect = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "mathvision-redis-reconnect"); thread.setDaemon(true); return thread;
    });
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("mathVisionEventListener")
    private org.springframework.beans.factory.ObjectProvider<RedisMessageListenerContainer> listenerProvider;
    @Bean public RedisMessageListenerContainer mathVisionEventListener(RedisConnectionFactory factory, MathVisionRedisEventBus bus) {
        RedisMessageListenerContainer listener = new RedisMessageListenerContainer() {
            @Override public boolean isAutoStartup() { return false; }
        };
        listener.setConnectionFactory(factory); listener.addMessageListener(bus, new ChannelTopic(MathVisionRedisEventBus.CHANNEL));
        listener.setRecoveryInterval(5000); return listener;
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void scheduleReconnect() {
        reconnect.scheduleWithFixedDelay(this::startWhenRedisAvailable, 1, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    public void startWhenRedisAvailable() {
        RedisMessageListenerContainer listener = listenerProvider.getIfAvailable();
        if (listener != null && !listener.isRunning()) {
            try { listener.start(); } catch (Exception ignored) { }
        }
    }

    @javax.annotation.PreDestroy public void stopReconnect() { reconnect.shutdownNow(); }
}
