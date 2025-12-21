package com.kwang.study.llm.core;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import lombok.*;
import org.springframework.util.Assert;

import java.util.*;
import java.util.stream.Collectors;

public class Tools {
    @Data
    public static abstract class Tool {
        @JsonIgnore
        private String toolCallId;
        @JsonIgnore
        public abstract boolean isTerminal();

        // 非终端方法需要重写它
        public Object execute() {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    }

    @EqualsAndHashCode(callSuper = true)
    @JsonClassDescription("用于向用户回复的工具")
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ReplayTool extends Tool {
        @JsonPropertyDescription("回复给用户的具体文本内容")
        public String message;

        @Override
        public boolean isTerminal() {
            return true;
        }
    }

    public static final List<Class<?>> SUPPORTED_TOOLS = List.of(ReplayTool.class);

    public static List<Tools.Tool> convert(ChatCompletionMessage message, List<Class<?>> toolClasses) {
        Optional<List<ChatCompletionMessageToolCall>> toolCallsTemp = message.toolCalls();
        if (toolCallsTemp.isEmpty()) {
            return List.of();
        }
        List<ChatCompletionMessageToolCall> toolCalls = toolCallsTemp.get();
        HashMap<String, Class<?>> map = new HashMap<>(toolCalls.size());
        toolClasses.forEach(toolCall -> {
            map.put(toolCall.getSimpleName(), toolCall);
        });

        return toolCalls.stream()
                .map(toolCall -> {
                    ChatCompletionMessageFunctionToolCall function = toolCall.asFunction();
                    Class<?> target = map.get(function.function().name());
                    Assert.notNull(target, String.format("模型所选择工具不在传入的toolClasses中, %s, %s", function.function().name(), toolClasses));
                    Tool tool = (Tool) function.function().arguments(target);
                    tool.setToolCallId(function.id());
                    return tool;
                }).collect(Collectors.toList());
    }
}