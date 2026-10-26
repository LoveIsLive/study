package com.kwang.study.mathvision.engine;

import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MathVisionExecutionScopeTest {
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
