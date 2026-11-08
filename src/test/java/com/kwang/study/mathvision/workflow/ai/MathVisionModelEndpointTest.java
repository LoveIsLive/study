package com.kwang.study.mathvision.workflow.ai;

import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class MathVisionModelEndpointTest {
    @Test void preservesProviderPathsAndUsesActualCompatibilityProtocol() {
        assertEquals("https://open.bigmodel.cn/api/paas/v4/chat/completions",
                MathVisionModelEndpoint.generationUri("zhipu", MathVisionModelEndpoint.baseUrl("zhipu", null), "glm").toString());
        assertEquals("https://proxy.invalid/v1/chat/completions",
                MathVisionModelEndpoint.generationUri("openai", "https://proxy.invalid/v1/", "model").toString());
        assertEquals("https://proxy.invalid/api/messages",
                MathVisionModelEndpoint.generationUri("anthropic", "https://proxy.invalid/api/messages", "claude").toString());
        assertEquals("https://proxy.invalid/v1beta/models/gemini:generateContent",
                MathVisionModelEndpoint.generationUri("google", "https://proxy.invalid/v1beta/models", "gemini").toString());
    }

    @Test void rejectsCredentialBearingBaseUrlsAndKeepsLogsFreeOfPrivatePaths() {
        assertThrows(IllegalArgumentException.class, () -> MathVisionModelEndpoint.generationUri("openai", "https://key@host.invalid/v1", "m"));
        assertThrows(IllegalArgumentException.class, () -> MathVisionModelEndpoint.generationUri("openai", "https://host.invalid/v1?key=private", "m"));
        assertEquals("https://host.invalid:8443", MathVisionModelEndpoint.logAddress(
                URI.create("https://host.invalid:8443/private-gateway-key/v1/chat/completions")));
    }
}
