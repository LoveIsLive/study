package com.kwang.study.mathvision.workflow.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiMessage {

    private String role;
    private List<AiContentPart> parts = new ArrayList<>();

    public AiMessage() {
    }

    public AiMessage(String role, List<AiContentPart> parts) {
        this.role = role;
        this.parts = parts != null ? parts : new ArrayList<>();
    }

    public static AiMessage system(String text) {
        return new AiMessage("system", List.of(AiContentPart.text(text)));
    }

    public static AiMessage user(List<AiContentPart> parts) {
        return new AiMessage("user", parts);
    }
}
