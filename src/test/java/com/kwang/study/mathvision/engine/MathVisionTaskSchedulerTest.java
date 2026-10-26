package com.kwang.study.mathvision.engine;

import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.mapper.MathVisionTaskMapper;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.service.MathVisionTaskNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MathVisionTaskSchedulerTest {
    private MathVisionTaskMapper tasks;
    private MathVisionModelCatalog catalog;
    private MathVisionTaskScheduler scheduler;
    private List<Runnable> dispatched;
    private Map<Long, String> claims;
    private AtomicLong time;

    @BeforeEach
    void setup() {
        tasks = mock(MathVisionTaskMapper.class);
        catalog = new MathVisionModelCatalog();
        dispatched = new ArrayList<>();
        claims = new HashMap<>();
        time = new AtomicLong(TimeUnit.SECONDS.toNanos(100));
        scheduler = new MathVisionTaskScheduler(tasks, mock(MathVisionStageRunner.class),
                mock(MathVisionTaskNotifier.class), mock(MathVisionTaskExecutionRegistry.class),
                catalog, dispatched::add, new Random(17), time::get);
        when(tasks.findClaimCandidates(anyInt())).thenReturn(List.of(1L, 2L, 3L, 4L));
        when(tasks.findById(anyLong())).thenAnswer(invocation -> {
            long id = invocation.getArgument(0);
            return MathVisionTask.builder().id(id).lockToken(claims.get(id)).currentVersion(1)
                    .currentStage("code_generation").status("running").build();
        });
    }

    @AfterEach
    void close() { scheduler.stop(); }

    private void allowAllClaims() {
        when(tasks.claimWithLease(anyLong(), anyString(), anyInt(), anyInt())).thenAnswer(invocation -> {
            claims.put(invocation.getArgument(0), invocation.getArgument(1));
            return 1;
        });
    }

    @Test
    void randomizesBatchAndOnlyDispatchesAvailableCapacity() {
        allowAllClaims();
        scheduler.pollQueuedTasks();
        assertEquals(2, dispatched.size());
        verify(tasks).findClaimCandidates(4);
        verify(tasks, times(2)).claimWithLease(anyLong(), anyString(), eq(120), eq(2));
        time.addAndGet(TimeUnit.SECONDS.toNanos(10));
        scheduler.pollQueuedTasks();
        verify(tasks, times(1)).findClaimCandidates(anyInt());
    }

    @Test
    void lostClaimsContinueThroughWholeShuffledBatchWithoutExecutingAnything() {
        scheduler.pollQueuedTasks();
        ArgumentCaptor<Long> attempts = ArgumentCaptor.forClass(Long.class);
        verify(tasks, times(4)).claimWithLease(attempts.capture(), anyString(), anyInt(), anyInt());
        List<Long> ids = attempts.getAllValues();
        assertEquals(java.util.Set.of(1L, 2L, 3L, 4L), new java.util.HashSet<>(ids));
        assertNotEquals(List.of(1L, 2L, 3L, 4L), ids);
        assertTrue(dispatched.isEmpty());
        scheduler.pollQueuedTasks();
        verify(tasks, times(1)).findClaimCandidates(anyInt());
    }

    @Test
    void losingFirstRaceStillFillsCapacityFromRemainingCandidates() {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        when(tasks.claimWithLease(anyLong(), anyString(), anyInt(), anyInt())).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) return 0;
            claims.put(invocation.getArgument(0), invocation.getArgument(1));
            return 1;
        });
        scheduler.pollQueuedTasks();
        assertEquals(3, attempts.get());
        assertEquals(2, dispatched.size());
        assertEquals(2, claims.size());
        assertEquals(2, new java.util.HashSet<>(claims.values()).size());
    }

    @Test
    void repeatedEmptyRoundsBackOffAndHonorTheNextPollDeadline() {
        when(tasks.findClaimCandidates(anyInt())).thenReturn(List.of());
        for (int i = 0; i < 20; i++) {
            scheduler.pollQueuedTasks();
            time.addAndGet(TimeUnit.SECONDS.toNanos(10));
        }
        scheduler.pollQueuedTasks();
        verify(tasks, times(21)).findClaimCandidates(anyInt());
        time.addAndGet(TimeUnit.SECONDS.toNanos(3));
        scheduler.pollQueuedTasks();
        verify(tasks, times(21)).findClaimCandidates(anyInt());
        time.addAndGet(TimeUnit.SECONDS.toNanos(2));
        scheduler.pollQueuedTasks();
        verify(tasks, times(22)).findClaimCandidates(anyInt());
    }

    @Test
    void mysqlFailureAlsoBacksOffInsteadOfRetryingEverySchedulerTick() {
        when(tasks.findClaimCandidates(anyInt())).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, scheduler::pollQueuedTasks);
        assertDoesNotThrow(scheduler::pollQueuedTasks);
        time.addAndGet(TimeUnit.MILLISECONDS.toNanos(250));
        assertDoesNotThrow(scheduler::pollQueuedTasks);
        verify(tasks, times(1)).findClaimCandidates(anyInt());
    }

    @Test
    void rejectedDispatchReleasesClaimAndStillBacksOff() {
        scheduler.stop();
        scheduler = new MathVisionTaskScheduler(tasks, mock(MathVisionStageRunner.class),
                mock(MathVisionTaskNotifier.class), mock(MathVisionTaskExecutionRegistry.class), catalog,
                command -> { throw new RejectedExecutionException("full"); }, new Random(17), time::get);
        allowAllClaims();
        scheduler.pollQueuedTasks();
        verify(tasks, times(4)).releaseUnstartedLease(anyLong(), anyString());
        scheduler.pollQueuedTasks();
        verify(tasks, times(1)).findClaimCandidates(anyInt());
    }

    @Test
    void startupJitterDefersPollingWithoutSleepingOnTheSchedulerThread() {
        scheduler.start();
        scheduler.pollQueuedTasks();
        verify(tasks, never()).findClaimCandidates(anyInt());
        time.addAndGet(TimeUnit.SECONDS.toNanos(5));
        scheduler.pollQueuedTasks();
        verify(tasks).findClaimCandidates(anyInt());
    }
}
