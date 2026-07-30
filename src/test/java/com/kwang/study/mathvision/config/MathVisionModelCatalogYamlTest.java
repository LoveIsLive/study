package com.kwang.study.mathvision.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathVisionModelCatalogYamlTest {

    @Test
    void canonicalNacosYamlBindsAllRuntimeOptions() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "math-vision", new ClassPathResource("nacos/math-vision.yaml"));
        Binder binder = new Binder(ConfigurationPropertySources.from(sources));
        MathVisionModelCatalog catalog = binder.bind("", Bindable.of(MathVisionModelCatalog.class)).get();

        assertEquals(5, catalog.getModelProviders().size());
        assertEquals(2, catalog.getWorkflow().getCodeGenerationMaxRetries());
        assertEquals(3, catalog.getWorkflow().getVisualDesignSceneMaxRetries());
        assertEquals(5, catalog.getWorkflow().getStoryboardValidationMaxRetries());
        assertEquals(3, catalog.getWorkflow().getCodeEvaluationMaxRetries());
        assertEquals(10, catalog.getWorkflow().getRenderMaxRetries());
        assertEquals(5, catalog.getWorkflow().getSceneEvaluationMaxRetries());
        assertEquals("low", catalog.getWorkflow().getRenderQuality());
        assertTrue(Boolean.TRUE.equals(catalog.getWorkflow().getAiTraceEnabled()));
        assertEquals(300, catalog.getModelDefaults().getRequestTimeoutSeconds());
        assertEquals(2, catalog.getModelDefaults().getEmptyResponseRetries());
        assertEquals(1_000L, catalog.getModelDefaults().getTransientRetryBaseDelayMillis());
        assertEquals(0.25D, catalog.getModelDefaults().getRateLimitJitterRatio());

        MathVisionModelCatalog.ModelCatalog glm = catalog.findModel("zhipu", "glm-5v-turbo");
        assertNotNull(glm);
        assertEquals(200_000, glm.getContextWindow());
        assertEquals(131_072, glm.getMaxOutputTokens());
        assertEquals(0.4D, catalog.findEnabled("zhipu").getTemperature());

        MathVisionModelCatalog.ModelCatalog claude = catalog.findModel("anthropic", "claude-opus-4-8");
        assertNotNull(claude);
        assertTrue(Boolean.TRUE.equals(catalog.findEnabled("anthropic").getAdaptiveThinking()));
        assertEquals("high", catalog.findEnabled("anthropic").getEffort());

        MathVisionModelCatalog.ModelCatalog kimi = catalog.findModel("moonshot", "kimi-k2.5");
        assertNotNull(kimi);
        assertEquals(1.0D, kimi.getTemperature());
        assertEquals(32_768, kimi.getMaxOutputTokens());
        assertEquals(3, catalog.findEnabled("moonshot").getTimeoutRetryAttempts());
    }
}
