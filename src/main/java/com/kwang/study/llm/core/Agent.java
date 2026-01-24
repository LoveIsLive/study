package com.kwang.study.llm.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.llm.mapper.ChatMemoryMapper;
import com.kwang.study.llm.pojo.ChatMemory;
import com.openai.models.chat.completions.ChatCompletionMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Component
public class Agent {
    @Resource(name = "taskExecutor")
    private Executor executor;

    @Resource
    private ChatMemoryMapper memory;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FileStorageService fileStorageService;

    // 编排执行llm.
    // 主要，context的systemPromp十分重要，需要在调用方按照业务逻辑拼装
    public ChatCompletionMessage invoke(LLMContext context) {
        LLM llm = LLM.create(context);

        Prompt prompt = Prompt.create();
        List<ChatMemory> memoryList = memory.findBySessionId(context.getSessionId());
        prompt.addHistory(memoryList, fileStorageService, objectMapper);

        // 执行次数，可以在context中动态配置
        int count = 0;
        do {
            ChatCompletionMessage message = llm.invoke(prompt, context, Tools.SUPPORTED_TOOLS);
            List<Tools.Tool> tools = Tools.convert(message, Tools.SUPPORTED_TOOLS);

            // 逻辑上来说，不可能同时存在终端操作和非终端操作
            boolean hasTerminal = false, hasNonTerminal = false;
            for (Tools.Tool tool : tools) {
                if (tool.isTerminal()) {
                    hasTerminal = true;
                } else {
                    hasNonTerminal = true;
                }
                if (hasTerminal && hasNonTerminal) {
                    throw new IllegalArgumentException("逻辑错误，同时存在终端操作和非终端操作");
                }
            }
            if (hasNonTerminal) {
                List<CompletableFuture<Object>> futureList = tools.stream()
                        .map(tool -> CompletableFuture.supplyAsync(tool::execute, executor))
                        .collect(Collectors.toList());

                CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                        futureList.toArray(new CompletableFuture[0])
                );

                List<Object> results = allFutures.thenApply(v -> futureList.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList())).join();

                prompt.addAssistant(message);
                for (int i = 0; i < tools.size(); i++) {
                    prompt.addToolMessage(tools.get(i).getToolCallId(), results.get(i));
                }
            } else {
                return message;
            }
            count++;
        } while (count < 5);

        throw new IllegalStateException("调用次数多于" + (count - 1) + "次");
    }

}
