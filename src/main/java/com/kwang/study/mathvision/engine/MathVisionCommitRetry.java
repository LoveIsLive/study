package com.kwang.study.mathvision.engine;

import com.kwang.study.mathvision.pojo.MathVisionTask;
import org.springframework.stereotype.Component;

/** Retry short database commits without repeating an expensive workflow or uploading again. */
@Component
public class MathVisionCommitRetry {
    private final MathVisionStageCommitService commits;
    public MathVisionCommitRetry(MathVisionStageCommitService commits) { this.commits = commits; }
    public void commit(MathVisionTask task, Runnable writes) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try { commits.commit(task, false, writes); return; }
            catch (org.springframework.dao.TransientDataAccessException | org.springframework.transaction.TransactionSystemException error) {
                last = error;
                if (commits.wasCommitted(task)) return;
                if (Thread.currentThread().isInterrupted()) throw new MathVisionLeaseLostException();
            }
        }
        throw last;
    }
}
