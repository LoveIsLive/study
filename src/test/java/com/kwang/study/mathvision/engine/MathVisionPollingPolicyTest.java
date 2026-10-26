package com.kwang.study.mathvision.engine;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MathVisionPollingPolicyTest {
    @Test
    void shufflesImmutableCandidateBatchesWithoutChangingOrDroppingIds() {
        MathVisionPollingPolicy policy = new MathVisionPollingPolicy(new Random(17));
        List<Long> candidates = List.of(1L, 2L, 3L, 4L);
        Set<Long> firstCandidates = new HashSet<>();
        Set<List<Long>> orders = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            List<Long> shuffled = policy.shuffledCandidates(candidates);
            assertEquals(candidates.size(), shuffled.size());
            assertEquals(new HashSet<>(candidates), new HashSet<>(shuffled));
            firstCandidates.add(shuffled.get(0));
            orders.add(shuffled);
        }
        assertEquals(List.of(1L, 2L, 3L, 4L), candidates);
        assertEquals(Set.of(1L, 2L, 3L, 4L), firstCandidates);
        assertTrue(orders.size() > 1);
    }

    @Test
    void sustainedIdleBacksOffAndSuccessfulDispatchesRestoreResponsivePolling() {
        MathVisionPollingPolicy policy = new MathVisionPollingPolicy(new Random(3));
        long first = policy.nextDelayMillis(false, 500, 5000);
        long idle = first;
        for (int i = 0; i < 40; i++) {
            idle = policy.nextDelayMillis(false, 500, 5000);
            assertTrue(idle >= 500 && idle <= 5000);
        }
        assertTrue(idle >= 3750);
        assertTrue(idle > first * 3);
        long busy = idle;
        for (int i = 0; i < 40; i++) {
            busy = policy.nextDelayMillis(true, 500, 5000);
            assertTrue(busy >= 500 && busy <= 5000);
        }
        assertTrue(busy <= 625);
    }

    @Test
    void jitterRemainsActiveAfterBothSuccessAndSaturatedIdle() {
        MathVisionPollingPolicy busy = new MathVisionPollingPolicy(new Random(5));
        MathVisionPollingPolicy idle = new MathVisionPollingPolicy(new Random(7));
        Set<Long> busyDelays = new HashSet<>();
        Set<Long> idleDelays = new HashSet<>();
        for (int i = 0; i < 30; i++) idle.nextDelayMillis(false, 500, 5000);
        for (int i = 0; i < 100; i++) {
            busyDelays.add(busy.nextDelayMillis(true, 500, 5000));
            idleDelays.add(idle.nextDelayMillis(false, 500, 5000));
        }
        assertTrue(busyDelays.size() > 1);
        assertTrue(idleDelays.size() > 1);
        assertTrue(busyDelays.stream().allMatch(delay -> delay >= 500 && delay <= 625));
        assertTrue(idleDelays.stream().allMatch(delay -> delay >= 3750 && delay <= 5000));
    }

    @Test
    void startupPollsAreSpreadAcrossTheConfiguredWindow() {
        MathVisionPollingPolicy policy = new MathVisionPollingPolicy(new Random(11));
        Set<Long> delays = new HashSet<>();
        for (int i = 0; i < 100; i++) delays.add(policy.initialDelayMillis(500, 5000));
        assertTrue(delays.size() > 1);
        assertTrue(delays.stream().allMatch(delay -> delay >= 500 && delay <= 5000));
    }

    @Test
    void changedOrInvalidBoundsAreClampedWithoutOverflow() {
        MathVisionPollingPolicy policy = new MathVisionPollingPolicy(new Random(13));
        for (int i = 0; i < 100; i++) {
            long delay = policy.nextDelayMillis(false, 500, Integer.MAX_VALUE);
            assertTrue(delay >= 500 && delay <= Integer.MAX_VALUE);
        }
        assertEquals(600, policy.nextDelayMillis(false, 600, 600));
        assertEquals(600, policy.nextDelayMillis(true, 600, 600));
        assertEquals(250, policy.nextDelayMillis(false, -1, -10));
        assertEquals(250, policy.initialDelayMillis(-1, -10));
        assertEquals(1000, policy.nextDelayMillis(true, 1000, 500));
    }
}
