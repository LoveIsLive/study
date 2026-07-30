package com.kwang.study.mathvision.workflow.util;

import com.kwang.study.mathvision.workflow.model.AiContentPart;
import com.kwang.study.mathvision.workflow.model.AiMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Transient per-node conversation context for multi-turn MathVision LLM calls.
 *
 * The platform nodes mostly assemble {@link AiMessage} lists directly, so this
 * class also exposes a static adapter that trims those lists with the same
 * policy: keep leading system/fixed context and the current user request,
 * remove old rolling turns first, and include tool schemas in the budget.
 */
public class NodeConversationContext {

    private static final Logger log = LoggerFactory.getLogger(NodeConversationContext.class);
    private static final double SAFETY_MARGIN = 0.90D;
    private static final int MESSAGE_OVERHEAD_TOKENS = 4;
    private static final int IMAGE_PART_TOKENS = 2048;

    private final int maxInputTokens;
    private final int maxRollingRounds;
    private final List<Message> pinnedMessages = new ArrayList<>();
    private final List<Message> rollingMessages = new ArrayList<>();
    private final Map<Long, PendingTurn> pendingTurns = new TreeMap<>();
    private long nextTurnSequence = 0L;
    private long nextCommittedTurnSequence = 0L;

    public NodeConversationContext(int maxInputTokens) {
        this(maxInputTokens, 0);
    }

    public NodeConversationContext(int maxInputTokens, int maxRollingRounds) {
        this.maxInputTokens = Math.max(maxInputTokens, 1);
        this.maxRollingRounds = Math.max(maxRollingRounds, 0);
    }

    public synchronized void setSystemMessage(String content) {
        pinnedMessages.removeIf(m -> "system".equals(m.role));
        pinnedMessages.add(0, new Message("system", content));
    }

    public synchronized void setFixedContextMessage(String content) {
        pinnedMessages.add(new Message("system", content));
    }

    public synchronized void setPinnedMessages(List<Message> messages) {
        pinnedMessages.clear();
        if (messages != null) {
            pinnedMessages.addAll(messages);
        }
    }

    public synchronized List<Message> getPinnedMessages() {
        return Collections.unmodifiableList(new ArrayList<>(pinnedMessages));
    }

    public synchronized void addUserMessage(String content) {
        rollingMessages.add(new Message("user", content));
        trimRollingToFitBudgetLocked();
    }

    public synchronized void addAssistantMessage(String content) {
        rollingMessages.add(new Message("assistant", content));
        trimRollingToFitBudgetLocked();
    }

    public synchronized void appendTurn(String userContent, String assistantContent) {
        rollingMessages.add(new Message("user", userContent));
        rollingMessages.add(new Message("assistant", assistantContent));
        trimRollingToFitBudgetLocked();
    }

    public synchronized void appendTurnRaw(String userContent, String assistantContent) {
        appendTurn(userContent, assistantContent);
    }

    public synchronized void appendTurnSummary(String userContent, String assistantContent) {
        appendTurn(userContent, assistantContent);
    }

    public synchronized List<Message> getRollingMessages() {
        return Collections.unmodifiableList(new ArrayList<>(rollingMessages));
    }

    public synchronized String getSystemContent() {
        StringBuilder sb = new StringBuilder();
        for (Message message : pinnedMessages) {
            if (!"system".equals(message.role)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(message.content);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    public synchronized String getLastUserContent() {
        for (int i = rollingMessages.size() - 1; i >= 0; i--) {
            Message message = rollingMessages.get(i);
            if ("user".equals(message.role)) {
                return message.content;
            }
        }
        for (int i = pinnedMessages.size() - 1; i >= 0; i--) {
            Message message = pinnedMessages.get(i);
            if ("user".equals(message.role)) {
                return message.content;
            }
        }
        return null;
    }

    public synchronized void clear() {
        pinnedMessages.clear();
        rollingMessages.clear();
        pendingTurns.clear();
        nextTurnSequence = 0L;
        nextCommittedTurnSequence = 0L;
    }

    public synchronized boolean isEmpty() {
        return pinnedMessages.isEmpty() && rollingMessages.isEmpty();
    }

    public synchronized int messageCount() {
        return pinnedMessages.size() + rollingMessages.size();
    }

    public synchronized List<Message> getMessages() {
        List<Message> all = new ArrayList<>(pinnedMessages.size() + rollingMessages.size());
        all.addAll(pinnedMessages);
        all.addAll(rollingMessages);
        return Collections.unmodifiableList(all);
    }

    public synchronized List<Message> snapshotWithUserMessage(String userContent) {
        List<Message> snapshot = new ArrayList<>(pinnedMessages.size() + rollingMessages.size() + 1);
        snapshot.addAll(pinnedMessages);
        snapshot.addAll(rollingMessages);
        snapshot.add(new Message("user", userContent));
        return snapshot;
    }

    public synchronized List<Message> snapshotWithUserMessage(String userContent,
                                                              String requestPayloadBudgetText) {
        List<Message> snapshot = snapshotWithUserMessage(userContent);
        trimSnapshotToFitBudget(snapshot, maxInputTokens, requestPayloadBudgetText);
        return snapshot;
    }

    public synchronized TurnReservation reserveTurn(String userContent) {
        return new TurnReservation(nextTurnSequence++, snapshotWithUserMessage(userContent));
    }

    public synchronized void appendReservedTurn(long turnSequence,
                                                String userContent,
                                                String assistantContent) {
        pendingTurns.put(turnSequence, new PendingTurn(userContent, assistantContent));
        flushPendingTurnsLocked();
        trimRollingToFitBudgetLocked();
    }

    public synchronized void cancelReservedTurn(long turnSequence) {
        pendingTurns.put(turnSequence, PendingTurn.skipped());
        flushPendingTurnsLocked();
    }

    public synchronized int estimateTotalTokens() {
        int total = 0;
        for (Message message : pinnedMessages) {
            total += message.estimatedTokens;
        }
        for (Message message : rollingMessages) {
            total += message.estimatedTokens;
        }
        return total;
    }

    public int getPromptInputBudgetTokens() {
        return maxInputTokens;
    }

    public synchronized void trimToFitBudget() {
        trimRollingToFitBudgetLocked();
    }

    private void trimRollingToFitBudgetLocked() {
        trimRollingToMaxRoundsLocked();

        int effectiveBudget = effectiveBudget(maxInputTokens);
        int pinnedTokens = estimateTokens(pinnedMessages);
        if (pinnedTokens >= effectiveBudget) {
            log.warn("Pinned messages alone exceed input budget: ~{} tokens >= budget {}",
                    pinnedTokens, effectiveBudget);
            return;
        }

        int rollingBudget = effectiveBudget - pinnedTokens;
        int rollingTokens = estimateTokens(rollingMessages);
        if (rollingTokens <= rollingBudget) {
            return;
        }

        int removedMessages = 0;
        int beforeTokens = rollingTokens;
        while (rollingTokens > rollingBudget && !rollingMessages.isEmpty()) {
            Message removed = rollingMessages.remove(0);
            rollingTokens -= removed.estimatedTokens;
            removedMessages++;
            if (!rollingMessages.isEmpty() && "assistant".equals(rollingMessages.get(0).role)) {
                Message pair = rollingMessages.remove(0);
                rollingTokens -= pair.estimatedTokens;
                removedMessages++;
            }
        }

        if (removedMessages > 0) {
            log.info("Rolling context trimmed: {} messages removed, rolling ~{} -> ~{} tokens (budget {})",
                    removedMessages, beforeTokens, rollingTokens, rollingBudget);
        }
    }

    private void trimRollingToMaxRoundsLocked() {
        if (maxRollingRounds <= 0) {
            return;
        }
        int rounds = countRoundsLocked();
        if (rounds <= maxRollingRounds) {
            return;
        }

        int removedMessages = 0;
        int roundsToRemove = rounds - maxRollingRounds;
        for (int i = 0; i < roundsToRemove && !rollingMessages.isEmpty(); i++) {
            rollingMessages.remove(0);
            removedMessages++;
            if (!rollingMessages.isEmpty() && "assistant".equals(rollingMessages.get(0).role)) {
                rollingMessages.remove(0);
                removedMessages++;
            }
        }
        if (removedMessages > 0) {
            log.info("Rolling context trimmed by round limit: {} messages removed, rounds {} -> {}",
                    removedMessages, rounds, countRoundsLocked());
        }
    }

    private int countRoundsLocked() {
        int rounds = 0;
        for (Message message : rollingMessages) {
            if ("user".equals(message.role)) {
                rounds++;
            }
        }
        return rounds;
    }

    private void flushPendingTurnsLocked() {
        while (true) {
            PendingTurn pending = pendingTurns.remove(nextCommittedTurnSequence);
            if (pending == null) {
                return;
            }
            if (!pending.skipped) {
                rollingMessages.add(new Message("user", pending.userContent));
                rollingMessages.add(new Message("assistant", pending.assistantContent));
            }
            nextCommittedTurnSequence++;
        }
    }

    public static void trimSnapshotToFitBudget(List<Message> snapshot, int maxInputTokens) {
        trimSnapshotToFitBudget(snapshot, maxInputTokens, "");
    }

    public static void trimSnapshotToFitBudget(List<Message> snapshot,
                                               int maxInputTokens,
                                               String requestPayloadBudgetText) {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        int effectiveBudget = effectiveBudget(maxInputTokens);
        int requestPayloadTokens = TokenEstimator.estimateTokens(requestPayloadBudgetText);
        if (estimateTokens(snapshot) + requestPayloadTokens <= effectiveBudget) {
            return;
        }

        int firstNonSystem = firstNonSystem(snapshot);
        while (estimateTokens(snapshot) + requestPayloadTokens > effectiveBudget) {
            int nonSystemCount = snapshot.size() - firstNonSystem;
            if (nonSystemCount <= 1) {
                break;
            }
            snapshot.remove(firstNonSystem);
            if (firstNonSystem < snapshot.size() && "assistant".equals(snapshot.get(firstNonSystem).role)) {
                snapshot.remove(firstNonSystem);
            }
        }

        warnIfStillOverBudget(estimateTokens(snapshot), requestPayloadTokens, effectiveBudget);
    }

    /**
     * Trims a platform AiMessage snapshot, preserving all leading system
     * messages and the final message, which is treated as the current request.
     */
    public static List<AiMessage> trimAiMessagesToFitBudget(List<AiMessage> messages,
                                                            int maxInputTokens,
                                                            String requestPayloadBudgetText) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<BudgetedAiMessage> snapshot = new ArrayList<>();
        for (AiMessage message : messages) {
            if (message != null) {
                snapshot.add(new BudgetedAiMessage(message, estimateAiMessageTokens(message), false));
            }
        }
        if (snapshot.isEmpty()) {
            return List.of();
        }
        markPinnedAiMessages(snapshot);

        int effectiveBudget = effectiveBudget(maxInputTokens);
        int requestPayloadTokens = TokenEstimator.estimateTokens(requestPayloadBudgetText);
        if (estimateAiSnapshotTokens(snapshot) + requestPayloadTokens <= effectiveBudget) {
            return copyAiMessages(snapshot);
        }

        int beforeCount = snapshot.size();
        int beforeTokens = estimateAiSnapshotTokens(snapshot);
        while (estimateAiSnapshotTokens(snapshot) + requestPayloadTokens > effectiveBudget) {
            int removableIndex = firstRemovableAiMessage(snapshot);
            if (removableIndex < 0) {
                break;
            }
            snapshot.remove(removableIndex);
            if (removableIndex < snapshot.size() && "assistant".equals(snapshot.get(removableIndex).message.getRole())
                    && !snapshot.get(removableIndex).pinned) {
                snapshot.remove(removableIndex);
            }
        }

        int afterTokens = estimateAiSnapshotTokens(snapshot);
        if (snapshot.size() < beforeCount) {
            log.info("MathVision AI prompt context trimmed: {} messages removed, messages ~{} -> ~{} tokens, tool/schema ~{} tokens, budget {}",
                    beforeCount - snapshot.size(), beforeTokens, afterTokens, requestPayloadTokens, effectiveBudget);
        }
        warnIfStillOverBudget(afterTokens, requestPayloadTokens, effectiveBudget);
        return copyAiMessages(snapshot);
    }

    public static int estimateAiMessagesTokens(List<AiMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (AiMessage message : messages) {
            if (message != null) {
                total += estimateAiMessageTokens(message);
            }
        }
        return total;
    }

    public static int estimateAiMessageTokens(AiMessage message) {
        if (message == null) {
            return 0;
        }
        int total = MESSAGE_OVERHEAD_TOKENS + TokenEstimator.estimateTokens(message.getRole());
        if (message.getParts() == null) {
            return total;
        }
        for (AiContentPart part : message.getParts()) {
            if (part == null) {
                continue;
            }
            if ("image".equals(part.getType())) {
                total += IMAGE_PART_TOKENS
                        + TokenEstimator.estimateTokens(part.getMimeType())
                        + Math.max(1, StringUtils.hasText(part.getDataBase64())
                        ? part.getDataBase64().length() / 8192
                        : 0);
            } else {
                total += TokenEstimator.estimateTokens(part.getText());
            }
        }
        return total;
    }

    private static void markPinnedAiMessages(List<BudgetedAiMessage> snapshot) {
        int i = 0;
        while (i < snapshot.size() && "system".equals(snapshot.get(i).message.getRole())) {
            snapshot.get(i).pinned = true;
            i++;
        }
        snapshot.get(snapshot.size() - 1).pinned = true;
    }

    private static int firstRemovableAiMessage(List<BudgetedAiMessage> snapshot) {
        for (int i = 0; i < snapshot.size(); i++) {
            if (!snapshot.get(i).pinned) {
                return i;
            }
        }
        return -1;
    }

    private static List<AiMessage> copyAiMessages(List<BudgetedAiMessage> snapshot) {
        List<AiMessage> result = new ArrayList<>(snapshot.size());
        for (BudgetedAiMessage message : snapshot) {
            result.add(message.message);
        }
        return result;
    }

    private static int estimateAiSnapshotTokens(List<BudgetedAiMessage> snapshot) {
        int total = 0;
        for (BudgetedAiMessage message : snapshot) {
            total += message.estimatedTokens;
        }
        return total;
    }

    private static int firstNonSystem(List<Message> snapshot) {
        int firstNonSystem = 0;
        while (firstNonSystem < snapshot.size() && "system".equals(snapshot.get(firstNonSystem).role)) {
            firstNonSystem++;
        }
        return firstNonSystem;
    }

    private static int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += message.estimatedTokens;
        }
        return total;
    }

    private static int effectiveBudget(int maxInputTokens) {
        return Math.max(1, (int) Math.floor(Math.max(maxInputTokens, 1) * SAFETY_MARGIN));
    }

    private static void warnIfStillOverBudget(int messageTokens, int requestPayloadTokens, int effectiveBudget) {
        int totalTokens = messageTokens + requestPayloadTokens;
        if (totalTokens <= effectiveBudget) {
            return;
        }
        log.warn("Prompt snapshot exceeds input budget after rolling context trim: ~{} tokens > budget {}. "
                        + "Proceeding without truncating pinned context or the current user prompt; local token counting is heuristic. "
                        + "If the provider rejects it, reduce fixed context, scene payload, tool schema size, or configured output-token reserve. "
                        + "(messages ~{}, request payload ~{})",
                totalTokens, effectiveBudget, messageTokens, requestPayloadTokens);
    }

    public static class Message {
        private final String role;
        private final String content;
        private final int estimatedTokens;

        public Message(String role, String content) {
            this.role = role;
            this.content = content == null ? "" : content;
            this.estimatedTokens = TokenEstimator.estimateTokens(this.content) + MESSAGE_OVERHEAD_TOKENS;
        }

        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }

        public int getEstimatedTokens() {
            return estimatedTokens;
        }
    }

    public static class TurnReservation {
        private final long sequence;
        private final List<Message> snapshot;

        private TurnReservation(long sequence, List<Message> snapshot) {
            this.sequence = sequence;
            this.snapshot = snapshot;
        }

        public long getSequence() {
            return sequence;
        }

        public List<Message> getSnapshot() {
            return snapshot;
        }
    }

    private static class PendingTurn {
        private final String userContent;
        private final String assistantContent;
        private final boolean skipped;

        private PendingTurn(String userContent, String assistantContent) {
            this.userContent = userContent;
            this.assistantContent = assistantContent;
            this.skipped = false;
        }

        private PendingTurn(boolean skipped) {
            this.userContent = "";
            this.assistantContent = "";
            this.skipped = skipped;
        }

        private static PendingTurn skipped() {
            return new PendingTurn(true);
        }
    }

    private static final class BudgetedAiMessage {
        private final AiMessage message;
        private final int estimatedTokens;
        private boolean pinned;

        private BudgetedAiMessage(AiMessage message, int estimatedTokens, boolean pinned) {
            this.message = message;
            this.estimatedTokens = estimatedTokens;
            this.pinned = pinned;
        }
    }
}
