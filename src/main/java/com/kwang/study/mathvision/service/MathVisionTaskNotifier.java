package com.kwang.study.mathvision.service;

import com.kwang.study.auth.mapper.UserMapper;
import com.kwang.study.auth.pojo.User;
import com.kwang.study.llm.mapper.ChatSessionMapper;
import com.kwang.study.llm.pojo.ChatSession;
import com.kwang.study.mathvision.dto.MathVisionTaskEventVO;
import com.kwang.study.mathvision.mapper.MathVisionTaskMapper;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;

@Service
public class MathVisionTaskNotifier {

    public static final String DESTINATION = "/queue/mathvision-task-events";
    private static final Logger log = LoggerFactory.getLogger(MathVisionTaskNotifier.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SimpMessagingTemplate messagingTemplate;
    private final MathVisionTaskMapper taskMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final UserMapper userMapper;

    public MathVisionTaskNotifier(SimpMessagingTemplate messagingTemplate,
                                  MathVisionTaskMapper taskMapper,
                                  ChatSessionMapper chatSessionMapper,
                                  UserMapper userMapper) {
        this.messagingTemplate = messagingTemplate;
        this.taskMapper = taskMapper;
        this.chatSessionMapper = chatSessionMapper;
        this.userMapper = userMapper;
    }

    public void notifyTaskChanged(Long taskId, String event) {
        if (taskId == null) {
            return;
        }
        Runnable sender = () -> sendNow(taskId, event);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sender.run();
                }
            });
            return;
        }
        sender.run();
    }

    private void sendNow(Long taskId, String event) {
        try {
            MathVisionTask task = taskMapper.findById(taskId);
            if (task == null || task.getUserId() == null) {
                return;
            }
            User user = userMapper.findById(task.getUserId());
            if (user == null || !StringUtils.hasText(user.getUsername())) {
                return;
            }
            messagingTemplate.convertAndSendToUser(user.getUsername(), DESTINATION, toEvent(task, event));
        } catch (Exception e) {
            log.warn("Failed to push MathVision task event, taskId={}, event={}: {}",
                    taskId, event, e.getMessage());
        }
    }

    private MathVisionTaskEventVO toEvent(MathVisionTask task, String event) {
        ChatSession session = StringUtils.hasText(task.getSessionId())
                ? chatSessionMapper.findBySessionId(task.getSessionId())
                : null;
        return MathVisionTaskEventVO.builder()
                .event(event)
                .taskId(task.getId())
                .sessionId(task.getSessionId())
                .title(session != null ? session.getTitle() : null)
                .status(task.getStatus())
                .currentStage(task.getCurrentStage())
                .failedStage(task.getFailedStage())
                .errorType(task.getErrorType())
                .errorMessage(task.getErrorMessage())
                .mode(task.getMode())
                .outputTarget(task.getOutputTarget())
                .providerCode(task.getProviderCode())
                .modelName(task.getModelName())
                .currentVersion(task.getCurrentVersion())
                .lastConfirmedStage(task.getLastConfirmedStage())
                .cancelRequested(task.getCancelRequested())
                .finalArtifactPath(task.getFinalArtifactPath())
                .finalArtifactType(task.getFinalArtifactType())
                .createTime(task.getCreateTime() != null ? task.getCreateTime().format(TS) : null)
                .updateTime(task.getUpdateTime() != null ? task.getUpdateTime().format(TS) : null)
                .build();
    }
}
