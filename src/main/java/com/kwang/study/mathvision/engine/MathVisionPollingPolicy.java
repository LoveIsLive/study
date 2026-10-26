package com.kwang.study.mathvision.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Per-worker candidate randomization and bounded, adaptive polling with jitter. */
final class MathVisionPollingPolicy {
    private final Random random;
    private long intervalMillis;

    MathVisionPollingPolicy(Random random) {
        this.random = random;
    }

    List<Long> shuffledCandidates(List<Long> candidates) {
        List<Long> shuffled = new ArrayList<>(candidates);
        Collections.shuffle(shuffled, random);
        return shuffled;
    }

    long initialDelayMillis(int configuredMin, int configuredMax) {
        long min = Math.max(250, configuredMin);
        return between(min, Math.max(min, configuredMax));
    }

    long nextDelayMillis(boolean dispatched, int configuredMin, int configuredMax) {
        long min = Math.max(250, configuredMin);
        long max = Math.max(min, configuredMax);
        intervalMillis = Math.max(min, Math.min(max, intervalMillis));
        // Empty polls, lost claim races and failed dispatches all count as no progress.
        intervalMillis = dispatched
                ? Math.max(min, intervalMillis * 4 / 5)
                : Math.min(max, intervalMillis + (intervalMillis + 1) / 2);
        long jitter = Math.max(1, intervalMillis / 4);
        return between(Math.max(min, intervalMillis - jitter), Math.min(max, intervalMillis + jitter));
    }

    private long between(long lower, long upper) {
        return lower == upper ? lower : lower + random.nextInt((int) (upper - lower + 1));
    }
}
