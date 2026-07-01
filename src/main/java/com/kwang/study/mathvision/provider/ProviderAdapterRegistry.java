package com.kwang.study.mathvision.provider;

import com.kwang.study.mathvision.enums.ProviderEnum;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 按 provider 注册所有 adapter, 供 service 按 providerCode 取用。
 */
@Component
public class ProviderAdapterRegistry {

    private final Map<ProviderEnum, LlmProviderAdapter> adapters = new EnumMap<>(ProviderEnum.class);

    public ProviderAdapterRegistry(List<LlmProviderAdapter> all) {
        for (LlmProviderAdapter adapter : all) {
            adapters.put(adapter.provider(), adapter);
        }
    }

    /** 取 adapter; 未注册返回 null。 */
    public LlmProviderAdapter get(ProviderEnum provider) {
        return provider == null ? null : adapters.get(provider);
    }
}
