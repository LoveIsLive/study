package com.kwang.study.llm.util;

import com.kwang.study.llm.config.LLMGlobalConfig;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class OpenAIClientManager {

    // 缓存 Client，Key 为 ApiKey
    private static final Map<String, OpenAIClient> clientCache = new ConcurrentHashMap<>();

    public static OpenAIClient getClient(LLMGlobalConfig.SceneConfig config) {
        return clientCache.computeIfAbsent(config.getApiKey(), k -> {
            OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                    .apiKey(config.getApiKey());
            if (config.getBaseUrl() != null && !config.getBaseUrl().isEmpty()) {
                builder.baseUrl(config.getBaseUrl());
            }
            return builder.build();
        });
    }
}