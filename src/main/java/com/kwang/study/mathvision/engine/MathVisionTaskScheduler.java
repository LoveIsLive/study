package com.kwang.study.mathvision.engine;

import com.kwang.study.mathvision.enums.StageEnum;
import com.kwang.study.mathvision.mapper.MathVisionTaskMapper;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.service.MathVisionTaskNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

@Component
public class MathVisionTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(MathVisionTaskScheduler.class);
    private static final List<String> RUNNABLE_STAGES = Arrays.asList(
            StageEnum.PROBLEM_NORMALIZATION.getCode(),
            StageEnum.REASONING_GRAPH.getCode(),
            StageEnum.VISUAL_STORYBOARD.getCode(),
            StageEnum.CODE_GENERATION.getCode(),
            StageEnum.RENDER_RESULT.getCode()
    );

    private final MathVisionTaskMapper taskMapper;
    private final MathVisionStageRunner stageRunner;
    private final MathVisionTaskNotifier taskNotifier;
    private final Executor executor;
    private final boolean enabled;
    private final int batchSize;

    public MathVisionTaskScheduler(MathVisionTaskMapper taskMapper,
                                   MathVisionStageRunner stageRunner,
                                   MathVisionTaskNotifier taskNotifier,
                                   @Qualifier("mathVisionTaskExecutor") Executor executor,
                                   @Value("${mathvision.scheduler.enabled:true}") boolean enabled,
                                   @Value("${mathvision.scheduler.batch-size:2}") int batchSize) {
        this.taskMapper = taskMapper;
        this.stageRunner = stageRunner;
        this.taskNotifier = taskNotifier;
        this.executor = executor;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTasks() {
        int recovered = taskMapper.resetRunningToFailed();
        if (recovered > 0) {
            log.warn("已恢复 {} 个启动前处于 running 的 MathVision 任务为 failed", recovered);
        }
    }

    @Scheduled(fixedDelayString = "${mathvision.scheduler.poll-delay-ms:2000}",
            initialDelayString = "${mathvision.scheduler.initial-delay-ms:3000}")
    public void pollQueuedTasks() {
        if (!enabled) {
            return;
        }
        List<MathVisionTask> tasks = taskMapper.findRunnableTasks(RUNNABLE_STAGES, Math.max(batchSize, 1));
        for (MathVisionTask task : tasks) {
            int claimed = taskMapper.claimRunnableTask(task.getId());
            if (claimed == 0) {
                continue;
            }
            log.debug("MathVision 任务已领取执行, taskId={}, stage={}", task.getId(), task.getCurrentStage());
            taskNotifier.notifyTaskChanged(task.getId(), "running");
            executor.execute(() -> stageRunner.runOneVisibleStage(task.getId()));
        }
    }
}
