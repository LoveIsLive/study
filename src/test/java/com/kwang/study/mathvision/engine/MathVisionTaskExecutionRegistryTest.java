package com.kwang.study.mathvision.engine;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MathVisionTaskExecutionRegistryTest {

    @Test
    void interruptsRegisteredTaskWorker() throws Exception {
        MathVisionTaskExecutionRegistry registry = new MathVisionTaskExecutionRegistry();
        CountDownLatch interrupted = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            registry.register(42L);
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(5));
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            } finally {
                registry.unregister(42L);
            }
        });
        worker.start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!registry.interrupt(42L) && System.nanoTime() < deadline) {
            Thread.yield();
        }

        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        worker.join(2_000L);
    }
}
