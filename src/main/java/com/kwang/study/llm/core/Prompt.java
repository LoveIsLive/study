package com.kwang.study.llm.core;

import com.kwang.study.llm.pojo.ChatMemory;
import com.openai.models.chat.completions.*;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

// 负责 user, ai, tool的消息
public class Prompt {

    // Getter 供 LLM 调用时获取底层 Message 列表
    @Getter
    private final List<ChatCompletionMessageParam> messages = new ArrayList<>();

    private Prompt() {}

    public static Prompt create() {
        return new Prompt();
    }

    /**
     * 批量加载历史记录 (从数据库 Entity 转换)
     */
    public Prompt addHistory(List<ChatMemory> memories) {
        if (memories == null) return this;
        for (ChatMemory mem : memories) {
            if ("user".equals(mem.getRole())) {
                this.addUser(mem.getContent());
            } else if ("assistant".equals(mem.getRole())) {
                this.addAssistant(mem.getContent());
            }
        }
        return this;
    }

    public Prompt addUser(String content) {
        messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder()
                .content(content).build()));
        return this;
    }

    public Prompt addAssistant(String content) {
        messages.add(ChatCompletionMessageParam.ofAssistant(ChatCompletionAssistantMessageParam.builder()
                .content(content).build()));
        return this;
    }

    public Prompt addToolMessage(String toolCallId, Object content) {
        messages.add(ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam.builder()
                .toolCallId(toolCallId)
                .contentAsJson(content)
                .build()));
        return this;
    }
}