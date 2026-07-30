package com.kwang.study.mathvision.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class MathVisionSchedulerConfig {

    @Bean("mathVisionTaskExecutor")
    public Executor mathVisionTaskExecutor(
            @Value("${mathvision.scheduler.core-pool-size:1}") int corePoolSize,
            @Value("${mathvision.scheduler.max-pool-size:2}") int maxPoolSize,
            @Value("${mathvision.scheduler.queue-capacity:20}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(30);
        executor.setThreadNamePrefix("mathvision-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}
