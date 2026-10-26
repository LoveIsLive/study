package com.kwang.study.mathvision.transfer;

import com.kwang.study.mathvision.config.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import javax.servlet.Filter;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

class MathVisionInternalSecurityTest {
    @Test void internalEndpointsRequireServiceCredentialAndDoNotMatchPublicApis() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class))
                .withUserConfiguration(TestConfig.class, MathVisionInternalSecurityConfig.class)
                .run(context -> {
                    var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
                    mvc.perform(get("/internal/mathvision/probe")).andExpect(status().isUnauthorized());
                    mvc.perform(get("/internal/mathvision/probe").header("X-MathVision-Key", "wrong")).andExpect(status().isUnauthorized());
                    mvc.perform(get("/internal/mathvision/probe").header("X-MathVision-Key", "test-key-with-at-least-32-characters")).andExpect(status().isOk());
                });
    }
    @Configuration @EnableWebMvc
    static class TestConfig {
        @Bean MathVisionTransferSettings settings() {
            var settings = mock(MathVisionTransferSettings.class); when(settings.secret()).thenReturn("test-key-with-at-least-32-characters"); return settings;
        }
        @Bean Probe probe() { return new Probe(); }
    }
    @RestController static class Probe {
        @GetMapping("/internal/mathvision/probe") String probe() { return "ok"; }
    }
}
