package com.kwang.study.mathvision.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Per-stage immutable configuration/runtime cache; never propagated to another pool thread. */
public final class MathVisionExecutionScope implements AutoCloseable {
    private static final ThreadLocal<Map<Object, Object>> CURRENT = new ThreadLocal<>();
    public MathVisionExecutionScope() { CURRENT.set(new HashMap<>()); }
    public static boolean active() { return CURRENT.get() != null; }
    @SuppressWarnings("unchecked")
    public static <T> T once(Object key, Supplier<T> loader) {
        Map<Object, Object> values = CURRENT.get();
        if (values == null) return loader.get();
        if (values.containsKey(key)) return (T) values.get(key);
        T value = loader.get(); // Loaders can populate other snapshot keys in this same thread.
        values.put(key, value);
        return value;
    }
    @Override public void close() { CURRENT.remove(); }
}
