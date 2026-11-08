package com.kwang.study.mathvision.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Per-stage immutable configuration/runtime cache; never propagated to another pool thread. */
public final class MathVisionExecutionScope implements AutoCloseable {
    private static final ThreadLocal<Map<Object, Object>> CURRENT = new ThreadLocal<>();
    private final Map<Object, Object> previous = CURRENT.get();
    public MathVisionExecutionScope() { CURRENT.set(new HashMap<>()); }
    private MathVisionExecutionScope(Map<Object, Object> values) { CURRENT.set(new HashMap<>(values)); }
    /** Captures the prepared stage configuration, without sharing a mutable thread-local map. */
    public static Snapshot snapshot() {
        if (!active()) throw new IllegalStateException("No MathVision execution scope");
        return new Snapshot(CURRENT.get());
    }
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
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }

    public static final class Snapshot {
        private final Map<Object, Object> values;
        private Snapshot(Map<Object, Object> values) { this.values = new HashMap<>(values); }
        public MathVisionExecutionScope open() { return new MathVisionExecutionScope(values); }
    }
}
