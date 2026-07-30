package com.kwang.study.mathvision.workflow.util;

import com.kwang.study.mathvision.workflow.model.AiContentPart;
import com.kwang.study.mathvision.workflow.model.AiMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeConversationContextTest {

    @Test
    void trimsRollingAiMessagesButKeepsSystemAndCurrentRequest() {
        AiMessage system = AiMessage.system("system rules");
        AiMessage current = AiMessage.user(List.of(AiContentPart.text("current request")));
        List<AiMessage> messages = new ArrayList<>();
        messages.add(system);
        for (int i = 0; i < 8; i++) {
            messages.add(AiMessage.user(List.of(AiContentPart.text(repeat("old user turn " + i + " ", 80)))));
            messages.add(new AiMessage("assistant", List.of(AiContentPart.text(repeat("old answer turn " + i + " ", 80)))));
        }
        messages.add(current);

        List<AiMessage> trimmed = NodeConversationContext.trimAiMessagesToFitBudget(
                messages, 500, repeat("tool schema ", 40));

        assertSame(system, trimmed.get(0));
        assertSame(current, trimmed.get(trimmed.size() - 1));
        assertTrue(trimmed.size() < messages.size());
        assertEquals("system", trimmed.get(0).getRole());
        assertEquals("user", trimmed.get(trimmed.size() - 1).getRole());
    }

    private String repeat(String text, int count) {
        StringBuilder sb = new StringBuilder(text.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(text);
        }
        return sb.toString();
    }
}
