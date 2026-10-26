package com.kwang.study.mathvision.engine;

import com.kwang.study.mathvision.mapper.MathVisionTaskMapper;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/** The task row is the lock ordering root for every MathVision write transaction. */
@Service
public class MathVisionStageCommitService {
    private final MathVisionTaskMapper tasks;
    private final TransactionTemplate transaction;
    private final com.kwang.study.mathvision.mapper.MathVisionVersionMapper versions;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public MathVisionStageCommitService(MathVisionTaskMapper tasks, PlatformTransactionManager manager,
            com.kwang.study.mathvision.mapper.MathVisionVersionMapper versions, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.tasks = tasks;
        this.versions = versions; this.json = json;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(30);
    }

    public void check(MathVisionTask execution) {
        MathVisionTask current = tasks.findById(execution.getId());
        if (current == null || !Objects.equals(current.getLockToken(), execution.getLockToken())
                || !Objects.equals(current.getCurrentVersion(), execution.getCurrentVersion())
                || !Objects.equals(current.getCurrentStage(), execution.getCurrentStage())
                || !"running".equals(current.getStatus()) || execution.getLockToken() == null
                || tasks.hasLease(execution.getId(), execution.getLockToken()) != 1) {
            throw new MathVisionLeaseLostException();
        }
        if (Boolean.TRUE.equals(current.getCancelRequested())) {
            throw new MathVisionTaskCanceledException();
        }
        if (Thread.currentThread().isInterrupted()) throw new MathVisionLeaseLostException();
    }

    public void commit(MathVisionTask execution, boolean cancellation, Runnable writes) {
        transaction.executeWithoutResult(tx -> {
            MathVisionTask current = tasks.lockById(execution.getId());
            if (wasCommitted(execution)) return;
            if (current == null || Boolean.TRUE.equals(current.getDeleted())
                    || !Objects.equals(current.getLockToken(), execution.getLockToken())
                    || !Objects.equals(current.getCurrentVersion(), execution.getCurrentVersion())
                    || !Objects.equals(current.getCurrentStage(), execution.getCurrentStage())
                    || !"running".equals(current.getStatus()) || execution.getLockToken() == null
                    || tasks.hasLease(execution.getId(), execution.getLockToken()) != 1) {
                throw new MathVisionLeaseLostException();
            }
            if (!cancellation && Boolean.TRUE.equals(current.getCancelRequested())) {
                throw new MathVisionTaskCanceledException();
            }
            writes.run();
            var version = versions.findByTaskVersion(execution.getId(), execution.getCurrentVersion());
            if (version != null) {
                com.fasterxml.jackson.databind.node.ObjectNode summary;
                try { summary = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(version.getWorkflowSummaryJson()); }
                catch (Exception ignored) { summary = json.createObjectNode(); }
                if (summary == null) summary = json.createObjectNode();
                var tokens = summary.with("executionCommits");
                tokens.put(execution.getLockToken(), execution.getCurrentStage());
                versions.updateWorkflowSummary(execution.getId(), execution.getCurrentVersion(), summary.toString());
            }
            // Check again at the transaction's linearization point, after every dependent write.
            if (tasks.releaseCommittedLease(execution.getId(), execution.getLockToken()) != 1) {
                throw new MathVisionLeaseLostException();
            }
        });
    }

    public boolean wasCommitted(MathVisionTask execution) {
        if (execution.getLockToken() == null) return false;
        var version = versions.findByTaskVersion(execution.getId(), execution.getCurrentVersion());
        try { return version != null && json.readTree(version.getWorkflowSummaryJson()).path("executionCommits").has(execution.getLockToken()); }
        catch (Exception ignored) { return false; }
    }
}
