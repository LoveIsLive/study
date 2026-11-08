package com.kwang.study.mathvision.engine;

import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.mapper.MathVisionTaskMapper;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.service.MathVisionTaskNotifier;
import com.kwang.study.mathvision.workflow.ai.MathVisionAiChatService;
import com.kwang.study.mathvision.workflow.ai.MathVisionModelReachability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.net.URI;
import java.time.LocalDateTime;
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
    private MathVisionAiChatService ai;
    private MathVisionModelReachability reachability;
    private List<MathVisionTask> candidates;

    @BeforeEach
    void setup() {
        tasks = mock(MathVisionTaskMapper.class);
        catalog = new MathVisionModelCatalog();
        dispatched = new ArrayList<>();
        claims = new HashMap<>();
        time = new AtomicLong(TimeUnit.SECONDS.toNanos(100));
        ai = mock(MathVisionAiChatService.class);
        reachability = mock(MathVisionModelReachability.class);
        scheduler = new MathVisionTaskScheduler(tasks, mock(MathVisionStageRunner.class),
                mock(MathVisionTaskNotifier.class), mock(MathVisionTaskExecutionRegistry.class),
                catalog, dispatched::add, new Random(17), time::get, ai, reachability);
        candidates = new ArrayList<>();
        for (long id = 1; id <= 4; id++) candidates.add(MathVisionTask.builder().id(id).userId(1L)
                .currentVersion(1).currentStage("code_generation").providerCode("openai").modelName("model")
                .updateTime(LocalDateTime.of(2026, 1, 1, 0, 0)).build());
        when(tasks.findModelClaimCandidates(nullable(LocalDateTime.class), nullable(Long.class), anyInt(), anyInt()))
                .thenReturn(candidates);
        when(ai.prepareExecution(any())).thenAnswer(invocation -> {
            MathVisionTask task = invocation.getArgument(0);
            var prepared = mock(MathVisionAiChatService.PreparedExecution.class);
            when(prepared.getEndpoint()).thenReturn(URI.create("https://provider" + task.getId() + ".invalid/v1/chat/completions"));
            when(prepared.openScope()).thenAnswer(ignored -> new MathVisionExecutionScope());
            return prepared;
        });
        when(reachability.canAccess(any(), anyLong())).thenReturn(true);
        when(tasks.findById(anyLong())).thenAnswer(invocation -> {
            long id = invocation.getArgument(0);
            return MathVisionTask.builder().id(id).lockToken(claims.get(id)).currentVersion(1)
                    .currentStage("code_generation").status("running").build();
        });
    }

    @AfterEach
    void close() { scheduler.stop(); }

    private void allowAllClaims() {
        when(tasks.claimWithModelLease(any(), anyString(), anyInt(), anyInt())).thenAnswer(invocation -> {
            claims.put(invocation.getArgument(0, MathVisionTask.class).getId(), invocation.getArgument(1));
            return 1;
        });
    }

    @Test
    void randomizesBatchAndOnlyDispatchesAvailableCapacity() {
        allowAllClaims();
        scheduler.pollQueuedTasks();
        assertEquals(2, dispatched.size());
        verify(tasks).findModelClaimCandidates(null, null, 4, 2);
        verify(tasks, times(2)).claimWithModelLease(any(), anyString(), eq(120), eq(2));
        time.addAndGet(TimeUnit.SECONDS.toNanos(10));
        scheduler.pollQueuedTasks();
        verify(tasks, times(1)).findModelClaimCandidates(nullable(LocalDateTime.class), nullable(Long.class), anyInt(), anyInt());
    }

    @Test
    void lostClaimsContinueThroughWholeShuffledBatchWithoutExecutingAnything() {
        scheduler.pollQueuedTasks();
        ArgumentCaptor<MathVisionTask> attempts = ArgumentCaptor.forClass(MathVisionTask.class);
        verify(tasks, times(4)).claimWithModelLease(attempts.capture(), anyString(), anyInt(), anyInt());
        List<Long> ids = attempts.getAllValues().stream().map(MathVisionTask::getId).collect(java.util.stream.Collectors.toList());
        assertEquals(java.util.Set.of(1L, 2L, 3L, 4L), new java.util.HashSet<>(ids));
        assertNotEquals(List.of(1L, 2L, 3L, 4L), ids);
        assertTrue(dispatched.isEmpty());
        scheduler.pollQueuedTasks();
        verify(tasks, times(1)).findModelClaimCandidates(nullable(LocalDateTime.class), nullable(Long.class), anyInt(), anyInt());
    }

    @Test
    void losingFirstRaceStillFillsCapacityFromRemainingCandidates() {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        when(tasks.claimWithModelLease(any(), anyString(), anyInt(), anyInt())).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) return 0;
            claims.put(invocation.getArgument(0, MathVisionTask.class).getId(), invocation.getArgument(1));
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
        when(tasks.findModelClaimCandidates(nullable(LocalDateTime.class), nullable(Long.class), anyInt(), anyInt())).thenReturn(List.of());
        for (int i = 0; i < 20; i++) {
            scheduler.pollQueuedTasks();
            time.addAndGet(TimeUnit.SECONDS.toNanos(10));
        }
        scheduler.pollQueuedTasks();
        verify(tasks, times(21)).findModelClaimCandidates(nullable(LocalDateTime.class), nullable(Long.class), anyInt(), anyInt());
        time.addAndGet(TimeUnit.SECONDS.toNanos(3));
        scheduler.pollQueuedTasks();
        verify(tasks, times(21)).findModelClaimCandidates(nullable(LocalDateTime.class), nullable(Long.class), anyInt(), anyInt());
        time.addAndGet(TimeUnit.SECONDS.toNanos(2));
        scheduler.pollQueuedTasks();
        verify(tasks, times(22)).findModelClaimCandidates(nullable(LocalDateTime.class), nullable(Long.class), anyInt(), anyInt());
    }

    @Test
    void mysqlFailureAlsoBacksOffInsteadOfRetryingEverySchedulerTick() {
        when(tasks.findModelClaimCandidates(nullable(LocalDateTime.class), nullable(Long.class), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, scheduler::pollQueuedTasks);
        assertDoesNotThrow(scheduler::pollQueuedTasks);
        time.addAndGet(TimeUnit.MILLISECONDS.toNanos(250));
        assertDoesNotThrow(scheduler::pollQueuedTasks);
        verify(tasks, times(1)).findModelClaimCandidates(nullable(LocalDateTime.class), nullable(Long.class), anyInt(), anyInt());
    }

    @Test
    void rejectedDispatchReleasesClaimAndStillBacksOff() {
        scheduler.stop();
        scheduler = new MathVisionTaskScheduler(tasks, mock(MathVisionStageRunner.class),
                mock(MathVisionTaskNotifier.class), mock(MathVisionTaskExecutionRegistry.class), catalog,
                command -> { throw new RejectedExecutionException("full"); }, new Random(17), time::get, ai, reachability);
        allowAllClaims();
        scheduler.pollQueuedTasks();
        verify(tasks, times(4)).releaseUnstartedLease(anyLong(), anyString());
        scheduler.pollQueuedTasks();
        verify(tasks, times(1)).findModelClaimCandidates(nullable(LocalDateTime.class), nullable(Long.class), anyInt(), anyInt());
    }

    @Test
    void startupJitterDefersPollingWithoutSleepingOnTheSchedulerThread() {
        scheduler.start();
        scheduler.pollQueuedTasks();
        verify(tasks, never()).findModelClaimCandidates(nullable(LocalDateTime.class), nullable(Long.class), anyInt(), anyInt());
        time.addAndGet(TimeUnit.SECONDS.toNanos(5));
        scheduler.pollQueuedTasks();
        verify(tasks).findModelClaimCandidates(nullable(LocalDateTime.class), nullable(Long.class), anyInt(), anyInt());
    }

    @Test
    void unreachableEndpointsNeverAcquireLeasesOrConsumeRecoveryBudget() {
        when(reachability.canAccess(any(), anyLong())).thenReturn(false);
        scheduler.pollQueuedTasks();
        assertTrue(dispatched.isEmpty());
        verify(tasks, never()).claimWithModelLease(any(), anyString(), anyInt(), anyInt());
        verify(tasks, never()).releaseUnstartedLease(anyLong(), anyString());
        verify(tasks, never()).markFailed(any(), any(), any(), any());
    }

    @Test
    void scansBeyondAnUnreachablePrefixAndWrapsAfterTheEnd() {
        when(reachability.canAccess(any(), anyLong())).thenReturn(false);
        when(tasks.findModelClaimCandidates(eq(candidates.get(3).getUpdateTime()), eq(4L), eq(4), eq(2)))
                .thenReturn(List.of());
        scheduler.pollQueuedTasks();
        time.addAndGet(TimeUnit.SECONDS.toNanos(10));
        scheduler.pollQueuedTasks();
        verify(tasks).findModelClaimCandidates(candidates.get(3).getUpdateTime(), 4L, 4, 2);
        verify(tasks, times(2)).findModelClaimCandidates(null, null, 4, 2);
    }

    @Test
    void skipsUnreachableTasksButClaimsReachableOnesInTheSameBatch() {
        allowAllClaims();
        when(reachability.canAccess(any(), eq(1L))).thenReturn(false);
        when(reachability.canAccess(any(), eq(2L))).thenReturn(false);
        scheduler.pollQueuedTasks();
        assertEquals(2, dispatched.size());
        assertEquals(java.util.Set.of(3L, 4L), claims.keySet());
    }

    @Test
    void invalidConfigurationDoesNotPreventOtherTasksFromBeingClaimed() {
        allowAllClaims();
        when(ai.prepareExecution(candidates.get(0))).thenThrow(new IllegalArgumentException("disabled model"));
        scheduler.pollQueuedTasks();
        assertEquals(2, dispatched.size());
        assertFalse(claims.containsKey(1L));
    }

    @Test
    void reachableTaskBeyondFirstBlockedBatchIsEventuallyDispatched() {
        when(reachability.canAccess(any(), anyLong())).thenReturn(false);
        MathVisionTask later = MathVisionTask.builder().id(5L).userId(1L).providerCode("custom_gateway")
                .modelName("reachable-model").currentVersion(1).currentStage("code_generation")
                .updateTime(LocalDateTime.of(2026, 1, 1, 0, 1)).build();
        when(tasks.findModelClaimCandidates(candidates.get(3).getUpdateTime(), 4L, 4, 2)).thenReturn(List.of(later));
        when(reachability.canAccess(any(), eq(5L))).thenReturn(true);
        allowAllClaims();

        scheduler.pollQueuedTasks();
        assertTrue(dispatched.isEmpty());
        time.addAndGet(TimeUnit.SECONDS.toNanos(10));
        scheduler.pollQueuedTasks();

        assertEquals(1, dispatched.size());
        assertEquals(java.util.Set.of(5L), claims.keySet());
    }

    @Test
    void postClaimModelSwitchDoesNotChangeTheCheckedStageSelection() {
        catalog.getDistributed().setConcurrency(1);
        when(tasks.findModelClaimCandidates(nullable(LocalDateTime.class), nullable(Long.class), anyInt(), anyInt()))
                .thenReturn(List.of(candidates.get(0)));
        allowAllClaims();
        when(tasks.findById(1L)).thenAnswer(ignored -> MathVisionTask.builder().id(1L).lockToken(claims.get(1L))
                .currentVersion(1).currentStage("code_generation").providerCode("new_provider").modelName("new-model").build());
        MathVisionStageRunner runner = mock(MathVisionStageRunner.class);
        scheduler.stop();
        scheduler = new MathVisionTaskScheduler(tasks, runner, mock(MathVisionTaskNotifier.class),
                mock(MathVisionTaskExecutionRegistry.class), catalog, dispatched::add, new Random(17), time::get, ai, reachability);

        scheduler.pollQueuedTasks();
        dispatched.get(0).run();

        ArgumentCaptor<MathVisionTask> execution = ArgumentCaptor.forClass(MathVisionTask.class);
        verify(runner).runOneVisibleStage(execution.capture());
        assertEquals("openai", execution.getValue().getProviderCode());
        assertEquals("model", execution.getValue().getModelName());
        assertFalse(MathVisionExecutionScope.active());
    }
}
