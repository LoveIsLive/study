package com.kwang.study.mathvision.engine;

import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MathVisionExecutionScopeTest {
    @Test void snapshotCanBeOpenedOnWorkerThreadAndNestedScopesRestoreTheirParent() throws Exception {
        Object key = new Object();
        MathVisionExecutionScope.Snapshot captured;
        try (var parent = new MathVisionExecutionScope()) {
            assertEquals("parent", MathVisionExecutionScope.once(key, () -> "parent"));
            try (var nested = new MathVisionExecutionScope()) {
                MathVisionExecutionScope.once(key, () -> "checked-model");
                captured = MathVisionExecutionScope.snapshot();
            }
            assertEquals("parent", MathVisionExecutionScope.once(key, () -> "unexpected"));
        }
        var thread = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result = thread.submit(() -> {
                try (var scope = captured.open()) {
                    return MathVisionExecutionScope.once(key, () -> "unexpected");
                }
            });
            assertEquals("checked-model", result.get(3, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(thread.submit(MathVisionExecutionScope::active).get());
        } finally { thread.shutdownNow(); }
        assertFalse(MathVisionExecutionScope.active());
    }
    @Test void nestedRuntimeLoadingCanPopulateConfigurationSnapshotsWithoutLeakingAcrossStages() {
        var catalog = new MathVisionModelCatalog();
        var liveWorkflow = catalog.getWorkflow(); liveWorkflow.setRenderMaxRetries(3);
        var loads = new AtomicInteger(); Object runtimeKey = new Object();
        try (var ignored = new MathVisionExecutionScope()) {
            Integer first = MathVisionExecutionScope.once(runtimeKey, () -> {
                loads.incrementAndGet(); return catalog.getWorkflow().getRenderMaxRetries();
            });
            liveWorkflow.setRenderMaxRetries(9);
            assertEquals(3, first);
            assertEquals(3, catalog.getWorkflow().getRenderMaxRetries());
            assertEquals(3, MathVisionExecutionScope.once(runtimeKey, () -> { fail("runtime loaded twice"); return 0; }));
            assertEquals(1, loads.get());
        }
        assertFalse(MathVisionExecutionScope.active()); assertEquals(9, catalog.getWorkflow().getRenderMaxRetries());
    }
}
