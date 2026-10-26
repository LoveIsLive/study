package com.kwang.study.mathvision.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class MathVisionSchedulerConfig {

    @Bean("mathVisionTaskExecutor")
    public Executor mathVisionTaskExecutor(MathVisionModelCatalog catalog) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int concurrency = Math.max(1, catalog.getDistributed().getConcurrency());
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(0);
        executor.setKeepAliveSeconds(30);
        executor.setThreadNamePrefix("mathvision-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
