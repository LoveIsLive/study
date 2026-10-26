package com.kwang.mathvision.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.mathvision.config.*;
import com.kwang.study.mathvision.engine.*;
import com.kwang.study.mathvision.service.*;
import com.kwang.study.mathvision.transfer.*;
import com.kwang.study.mathvision.util.ApiKeyCipher;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.*;
import java.util.*;

/** Deliberately outside com.kwang.study: the platform's component scan cannot load this entrypoint. */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class})
@EnableScheduling
@MapperScan("com.kwang.study.mathvision.mapper")
@ComponentScan(basePackages = {"com.kwang.study.mathvision.engine", "com.kwang.study.mathvision.workflow", "com.kwang.study.mathvision.provider"})
@Import({MathVisionModelCatalog.class, MathVisionSchedulerConfig.class, MathVisionRedisConfig.class,
        MathVisionFinalArtifactStorageService.class, MathVisionFinalCodeArtifactService.class,
        MathVisionWorkflowSummaryService.class, ApiKeyCipher.class, MathVisionTransferClient.class,
        MathVisionTransferSettings.class, MathVisionArtifactReceipt.class, MathVisionWorkerApplication.WorkerBeans.class})
public class MathVisionWorkerApplication {
    public static void main(String[] args) {
        // A worker is never a public web server, even if study's Nacos configuration enables web features.
        System.setProperty("mathvision.worker.enabled", "true");
        SpringApplication app = new SpringApplication(MathVisionWorkerApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(Collections.singletonMap("spring.main.web-application-type", "none"));
        app.run(args);
    }
    @Configuration
    public static class WorkerBeans {
        @Bean public org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer mathVisionMapperConfiguration() {
            return configuration -> configuration.setMapUnderscoreToCamelCase(true);
        }
        @Bean public MathVisionTaskNotifier workerTaskNotifier(MathVisionRedisEventBus bus) {
            return new MathVisionTaskNotifier(null, null, null, null) {
                @Override public void notifyTaskChanged(Long id, String event) {
                    Runnable publish = () -> bus.publish(id, event);
                    if (TransactionSynchronizationManager.isSynchronizationActive()) {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override public void afterCommit() { publish.run(); }
                        });
                    } else publish.run();
                }
                @Override public void deliverRemote(Long id, String event) { }
            };
        }
        @Bean public ApplicationRunner requireWorkerSecret(MathVisionTransferSettings settings) {
            return args -> settings.secret();
        }
    }
}
