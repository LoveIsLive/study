package com.kwang.study.llm.core;

import com.kwang.study.llm.config.LLMGlobalConfig;
import com.kwang.study.llm.util.OpenAIClientManager;
import com.openai.client.OpenAIClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.*;
import org.springframework.util.Assert;

import javax.xml.crypto.Data;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// LLM是无状态的，不可变的。
public class LLM {

    private final OpenAIClient client;
    // 核心状态：Builder
    private final ChatCompletionCreateParams params;

    // 私有构造，初始化基础配置
    private LLM(LLMContext context) {
        LLMGlobalConfig.SceneConfig config = context.getLlmConfig();

        // 1. 获取复用的 Client
        this.client = OpenAIClientManager.getClient(config);

        // 2. 初始化 Builder，填充通用参数
        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                .model(config.getModelName())
                .temperature(config.getTemperature() != null ? config.getTemperature() : 0.7);

        // 3. 填充 System Prompt
        if (config.getSystemPrompt() != null && !config.getSystemPrompt().isEmpty()) {
            paramsBuilder.addSystemMessage(config.getSystemPrompt());
        } else {
            paramsBuilder.addSystemMessage("当前时间为：" +
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()));
        }
        // 只要一个choice
        paramsBuilder.n(1);
        this.params = paramsBuilder.build();
    }

    /**
     * 静态工厂方法：每个请求创建一个新的 LLM 实例
     */
    public static LLM create(LLMContext context) {
        if (context == null || context.getLlmConfig() == null) {
            throw new IllegalArgumentException("LLMContext is required");
        }
        return new LLM(context);
    }

    /**
     * 执行对话或任务
     * @param prompt 包含本次对话的历史记录和新消息
     * @return Tool 列表
     */
    public List<Tools.Tool> invoke(Prompt prompt, LLMContext context) {
        ChatCompletionCreateParams.Builder paramsBuilder = params.toBuilder();

        if (params.tools().isEmpty()) {
            paramsBuilder.tools(new ArrayList<>()); // 库有问题需要先重置一下
        }

        // 工具集的选择应该也是可以按场景配置的，需要LLMContext
        List<Class<?>> tools = Tools.SUPPORTED_TOOLS;
        tools.forEach(paramsBuilder::addTool);
        // 必须调用tool
        paramsBuilder.toolChoice(ChatCompletionToolChoiceOption.Auto.REQUIRED);

        prompt.getMessages().forEach(paramsBuilder::addMessage);

        ChatCompletion completion = client.chat().completions().create(paramsBuilder.build());
        ChatCompletionMessage message = completion.choices().get(0).message();

        Assert.isTrue(message.toolCalls().isPresent(), "模型没有调用tool");

        return message.toolCalls().get().stream()
                .map(toolCall -> Tools.convert(toolCall.asFunction(), tools))
                .collect(Collectors.toList());
    }

    /**
     * 流式文本生成
     */
    public Stream<String> stream(Prompt prompt, LLMContext context) {
        ChatCompletionCreateParams.Builder paramsBuilder = params.toBuilder();
        // 1. 追加消息
        prompt.getMessages().forEach(paramsBuilder::addMessage);

        // 2. 发起流式请求
        StreamResponse<ChatCompletionChunk> streamResponse =
                client.chat().completions().createStreaming(paramsBuilder.build());

        // 3. 转换流
        return streamResponse.stream()
                .flatMap(chunk -> chunk.choices().stream())
                .flatMap(choice -> choice.delta().content().stream())
                .onClose(streamResponse::close);
    }
}