package com.kwang.study.llm.core;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Objects;

public class Tools {
    @Data
    public static abstract class Tool {
        @JsonIgnore
        private String toolCallId;
        @JsonIgnore
        public abstract boolean isTerminal();

        // 需要返回给前端
        public String getName() {
            return this.getClass().getSimpleName();
        }

        // 非终端方法需要重写它
        public Object execute() {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    }

    @JsonClassDescription("用于向用户回复的工具")
    public static class ReplayTool extends Tool {
        @JsonPropertyDescription("回复给用户的具体文本内容")
        public String message;

        @Override
        public boolean isTerminal() {
            return true;
        }
    }

    public static final List<Class<?>> SUPPORTED_TOOLS = List.of(ReplayTool.class);

    public static Tools.Tool convert(ChatCompletionMessageFunctionToolCall function, List<Class<?>> classes) {
        for (Class<?> aClass : classes) {
            if (Objects.equals(aClass.getSimpleName(), function.function().name())) {
                Tools.Tool tool = (Tools.Tool) function.function().arguments(aClass);
                tool.setToolCallId(function.id());
                return tool;
            }
        }
        throw new IllegalArgumentException("工具调用不匹配");
    }
}