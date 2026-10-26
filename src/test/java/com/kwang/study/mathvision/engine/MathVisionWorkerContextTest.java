package com.kwang.study.mathvision.engine;

import com.kwang.mathvision.worker.MathVisionWorkerApplication;
import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MathVisionWorkerContextTest {
    @Test void workerLoadsExecutorsWithoutPlatformControllersOrFileStorage() {
        new ApplicationContextRunner().withUserConfiguration(MathVisionWorkerApplication.class)
                .withPropertyValues("mathvision.worker.enabled=true", "mathvision.service-secret=test-secret-with-at-least-32-characters",
                        "spring.datasource.url=jdbc:mysql://127.0.0.1:33079/unused", "spring.datasource.username=root",
                        "spring.main.web-application-type=none", "nacos.config.enabled=false", "nacos.config.bootstrap.enable=false",
                        "nacos.config.group=test", "spring.autoconfigure.exclude=com.alibaba.boot.nacos.config.autoconfigure.NacosConfigAutoConfiguration")
                .withBean(MathVisionModelCatalog.class, () -> {
                    var catalog = new MathVisionModelCatalog(); catalog.getApikey().setSecret("test-api-key-secret-at-least-32-characters"); return catalog;
                })
                .run(context -> {
                    if (context.getStartupFailure() != null) throw new AssertionError("Worker startup failed", context.getStartupFailure());
                    assertEquals(5, context.getBeansOfType(MathVisionStageExecutor.class).size());
                    assertTrue(context.getBeansOfType(com.kwang.study.fs.service.FileStorageService.class).isEmpty());
                    assertTrue(context.getBeansOfType(com.kwang.study.mathvision.controller.MathVisionTaskController.class).isEmpty());
                    assertTrue(context.getBeansOfType(com.kwang.study.llm.mapper.ChatSessionMapper.class).isEmpty());
                });
    }
}
