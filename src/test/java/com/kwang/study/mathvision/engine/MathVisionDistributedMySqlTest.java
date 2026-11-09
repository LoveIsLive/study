package com.kwang.study.mathvision.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.mathvision.mapper.*;
import com.kwang.study.mathvision.pojo.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses a fresh, randomly named schema on the explicitly supplied disposable MySQL endpoint. */
@EnabledIfEnvironmentVariable(named = "MV_MYSQL_URL", matches = ".+")
class MathVisionDistributedMySqlTest {
    private static String schema, processUrl;
    private static DataSource admin, source;
    private MathVisionTaskMapper tasks;
    private LlmModelConfigMapper modelConfigs;
    private MathVisionArtifactMapper artifacts;
    private MathVisionVersionMapper versions;
    private MathVisionStageCommitService commits;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    @BeforeAll static void database() throws Exception {
        String url = System.getenv("MV_MYSQL_URL"), user = System.getenv().getOrDefault("MV_MYSQL_USER", "root"), password = System.getenv().getOrDefault("MV_MYSQL_PASSWORD", "");
        admin = new DriverManagerDataSource(url, user, password);
        schema = "mathvision_it_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(admin).execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
        int query = url.indexOf('?'); String base = query < 0 ? url : url.substring(0, query), args = query < 0 ? "" : url.substring(query);
        processUrl = base.replaceAll("/+$", "") + "/" + schema + args;
        source = new DriverManagerDataSource(processUrl, user, password);
        try (var connection = source.getConnection()) { ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/mathvision_schema.sql")); }
    }
    @AfterAll static void drop() {
        if (schema != null && schema.matches("mathvision_it_[a-f0-9]{32}")) new JdbcTemplate(admin).execute("DROP DATABASE " + schema);
    }
    @BeforeEach void setup() throws Exception {
        jdbc = new JdbcTemplate(source);
        var configuration = new org.apache.ibatis.session.Configuration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(MathVisionTaskMapper.class); configuration.addMapper(MathVisionArtifactMapper.class); configuration.addMapper(MathVisionVersionMapper.class);
        configuration.addMapper(LlmModelConfigMapper.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject());
        tasks = session.getMapper(MathVisionTaskMapper.class); artifacts = session.getMapper(MathVisionArtifactMapper.class); versions = session.getMapper(MathVisionVersionMapper.class);
        modelConfigs = session.getMapper(LlmModelConfigMapper.class);
        manager = new DataSourceTransactionManager(source); commits = new MathVisionStageCommitService(tasks, manager, versions, new ObjectMapper());
    }
    private long task() {
        var task = MathVisionTask.builder().sessionId(UUID.randomUUID().toString()).userId(1L).mode("auto").outputTarget("manim")
                .status("queued").currentStage("code_generation").currentVersion(1).build(); tasks.insert(task);
        versions.insert(MathVisionVersion.builder().taskId(task.getId()).version(1).changeSource("initial_generation").isCurrent(true).build()); return task.getId();
    }

    @Test void workerTestSuccessCannotOverwriteChangedOrDisabledCredential() {
        var config = LlmModelConfig.builder().ownerUserId(101L).provider("custom_" + UUID.randomUUID())
                .isCustom(true).compatibilityType("openai").baseUrl("https://gateway.invalid/v1")
                .modelName("model").apiKeyEncrypted("ciphertext-original").status("invalid").build();
        modelConfigs.insert(config);
        var expected = modelConfigs.findById(config.getId());
        var passed = new LlmModelConfig(); passed.setStatus("enabled");
        passed.setLastTestTime(java.time.LocalDateTime.now()); passed.setLastTestResult("Worker 测试通过");
        assertEquals(1, modelConfigs.updateTestResultIfUnchanged(passed, expected));
        assertEquals("enabled", modelConfigs.findById(config.getId()).getStatus());
        jdbc.update("UPDATE llm_model_configs SET api_key_encrypted='ciphertext-new' WHERE id=?", config.getId());
        assertEquals(0, modelConfigs.updateTestResultIfUnchanged(passed, expected));
        expected = modelConfigs.findById(config.getId());
        jdbc.update("UPDATE llm_model_configs SET base_url='https://other.invalid/v1' WHERE id=?", config.getId());
        assertEquals(0, modelConfigs.updateTestResultIfUnchanged(passed, expected));
        expected = modelConfigs.findById(config.getId());
        jdbc.update("UPDATE llm_model_configs SET status='disabled' WHERE id=?", config.getId());
        assertEquals(0, modelConfigs.updateTestResultIfUnchanged(passed, expected));
        assertEquals("disabled", modelConfigs.findById(config.getId()).getStatus());
    }
    private MathVisionTask claim(long id) {
        assertEquals(1, tasks.claimWithLease(id, UUID.randomUUID().toString(), 120, 2)); return tasks.findById(id);
    }
    @Test void autoReviewDefaultsToEnabledAndRuntimeSettingIsPersisted() {
        long id = task();
        assertTrue(tasks.findById(id).getAutoQualityReview());
        assertEquals(1, tasks.updateRuntimeSettings(id, 1L, "auto", null, null, null, false));
        assertFalse(tasks.findById(id).getAutoQualityReview());
        var execution = claim(id);
        assertFalse(execution.getAutoQualityReview());
        assertEquals(1, tasks.updateRuntimeSettings(id, 1L, "manual", null, null, null, true));
        assertTrue(tasks.findById(id).getAutoQualityReview());
    }
    @Test void modelAwareClaimRejectsStaleTaskSelectionAndStillAllowsOnlyOneWorker() throws Exception {
        long id = task();
        jdbc.update("UPDATE mathvision_tasks SET provider_code='custom_a', model_name='ModelA', selected_model_config_id=1 WHERE id=?", id);
        var candidate = tasks.findById(id);
        jdbc.update("UPDATE mathvision_tasks SET model_name='ModelB' WHERE id=?", id);
        assertEquals(0, tasks.claimWithModelLease(candidate, UUID.randomUUID().toString(), 120, 2));
        assertEquals("queued", tasks.findById(id).getStatus()); assertEquals(0, tasks.findById(id).getRecoveryCount());
        var fresh = tasks.findById(id);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            CountDownLatch start = new CountDownLatch(1); List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) results.add(pool.submit(() -> {
                start.await(); return tasks.claimWithModelLease(fresh, UUID.randomUUID().toString(), 120, 2);
            }));
            start.countDown(); int count = 0; for (Future<Integer> result : results) count += result.get(20, TimeUnit.SECONDS);
            assertEquals(1, count);
        } finally { pool.shutdownNow(); }
    }

    @Test void modelAwareClaimChecksTaskVersionStageCredentialAndCaseSensitiveModel() {
        long id = task(); var candidate = tasks.findById(id);
        jdbc.update("UPDATE mathvision_tasks SET current_version=2 WHERE id=?", id);
        assertEquals(0, tasks.claimWithModelLease(candidate, "stale-version", 120, 2));
        candidate = tasks.findById(id);
        jdbc.update("UPDATE mathvision_tasks SET current_stage='render_result' WHERE id=?", id);
        assertEquals(0, tasks.claimWithModelLease(candidate, "stale-stage", 120, 2));
        candidate = tasks.findById(id);
        jdbc.update("UPDATE mathvision_tasks SET selected_model_config_id=9 WHERE id=?", id);
        assertEquals(0, tasks.claimWithModelLease(candidate, "stale-credential", 120, 2));
        jdbc.update("UPDATE mathvision_tasks SET model_name='ModelA' WHERE id=?", id);
        candidate = tasks.findById(id);
        jdbc.update("UPDATE mathvision_tasks SET model_name='modela' WHERE id=?", id);
        assertEquals(0, tasks.claimWithModelLease(candidate, "stale-case", 120, 2));
        assertEquals(1, tasks.claimWithModelLease(tasks.findById(id), "current-selection", 120, 2));
    }

    @Test void candidateKeysetCanReadBeyondAnUnreachableQueuePrefix() {
        long first = task(), second = task(), third = task();
        for (long id : List.of(first, second, third))
            jdbc.update("UPDATE mathvision_tasks SET update_time='2000-01-01 00:00:00' WHERE id=?", id);
        var batch = tasks.findModelClaimCandidates(null, null, 2, 2);
        assertEquals(List.of(first, second), List.of(batch.get(0).getId(), batch.get(1).getId()));
        var tail = batch.get(1);
        assertEquals(third, tasks.findModelClaimCandidates(tail.getUpdateTime(), tail.getId(), 1, 2).get(0).getId());
        assertNotNull(tail.getUserId()); assertEquals("code_generation", tail.getCurrentStage());
        // Do not leave old-dated queued rows for other test methods.
        jdbc.update("UPDATE mathvision_tasks SET update_time=NOW() WHERE id IN (?,?,?)", first, second, third);
    }
    @Test void concurrentWorkersOnlyClaimOnce() throws Exception {
        long id = task(); ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1); List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(pool.submit(() -> { start.await(); return tasks.claimWithLease(id, UUID.randomUUID().toString(), 120, 2); }));
            start.countDown(); int count = 0; for (Future<Integer> result : results) count += result.get(20, TimeUnit.SECONDS);
            assertEquals(1, count); assertEquals(0, tasks.findById(id).getRecoveryCount());
        } finally { pool.shutdownNow(); }
    }
    @Test void expiredWorkerCannotRenewOrWriteAfterTakeover() {
        long id = task(); var old = claim(id); jdbc.update("UPDATE mathvision_tasks SET lease_until = NOW(3) - INTERVAL 1 SECOND WHERE id=?", id);
        var replacement = claim(id); assertEquals(1, replacement.getRecoveryCount()); assertEquals(0, tasks.renewLease(id, old.getLockToken(), 120));
        assertThrows(MathVisionLeaseLostException.class, () -> commits.commit(old, false, () -> tasks.markFailed(id, "code_generation", "workflow_error", "stale")));
        assertEquals("running", tasks.findById(id).getStatus());
        commits.commit(replacement, false, () -> tasks.markWaitingConfirm(id, "code_generation")); assertEquals("waiting_confirm", tasks.findById(id).getStatus());
    }
    @Test void expirationAtCommitRollsBackArtifactAndStatusTogether() {
        long id = task(); var run = claim(id);
        assertThrows(MathVisionLeaseLostException.class, () -> commits.commit(run, false, () -> {
            artifacts.insert(MathVisionArtifact.builder().taskId(id).sessionId(run.getSessionId()).userId(1L).stage("code_generation").version(1).artifactJson("{}").changeSource("initial_generation").build());
            tasks.markWaitingConfirm(id, "code_generation"); jdbc.update("UPDATE mathvision_tasks SET lease_until = NOW(3) - INTERVAL 1 SECOND WHERE id=?", id);
        }));
        assertEquals("running", tasks.findById(id).getStatus()); assertNull(artifacts.findByTaskStageVersion(id, "code_generation", 1));
    }
    @Test void cancellationRejectsResultsAndOldCancellationCannotAffectNewRun() {
        long id = task(); var old = claim(id); assertEquals(1, tasks.requestCancelRunning(id, 1L));
        assertThrows(MathVisionTaskCanceledException.class, () -> commits.commit(old, false, () -> fail("canceled writes")));
        commits.commit(old, true, () -> tasks.markCanceled(id)); tasks.queueTaskForRun(id, 1L, "code_generation", null); var newer = claim(id);
        commits.commit(old, true, () -> fail("already committed cancellation must not repeat"));
        assertEquals(newer.getLockToken(), tasks.findById(id).getLockToken());
    }
    @Test void duplicateCommitIsIdempotentAndDoesNotRepeatWrites() {
        long id = task(); var run = claim(id); commits.commit(run, false, () -> tasks.markWaitingConfirm(id, "code_generation"));
        assertTrue(commits.wasCommitted(run)); commits.commit(run, false, () -> fail("duplicate execution")); assertEquals("waiting_confirm", tasks.findById(id).getStatus());
    }
    @Test void expiredCancellationIsSettledRatherThanExecutedAgain() {
        long id = task(); claim(id); tasks.requestCancelRunning(id, 1L);
        jdbc.update("UPDATE mathvision_tasks SET lease_until = NOW(3) - INTERVAL 1 SECOND WHERE id=?", id); tasks.settleExpiredLeases(2);
        assertEquals("canceled", tasks.findById(id).getStatus()); assertEquals(0, tasks.claimWithLease(id, UUID.randomUUID().toString(), 120, 2));
    }
    @Test void rowLockSerializesVersionAllocationAcrossConnections() throws Exception {
        long id = task(); ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> {
                TransactionTemplate tx = new TransactionTemplate(manager); tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
                tx.executeWithoutResult(status -> { tasks.lockById(id); int next = versions.findMaxVersion(id) + 1;
                    versions.insert(MathVisionVersion.builder().taskId(id).version(next).changeSource("manual_edit").isCurrent(false).build()); });
            }));
            for (Future<?> future : futures) future.get(20, TimeUnit.SECONDS); assertEquals(5, versions.findMaxVersion(id));
        } finally { pool.shutdownNow(); }
    }

    @Test void separateJvmWorkerIsFencedAfterAnotherJvmTakesOver() throws Exception {
        long id = task();
        Process old = child(id, "hold"); Process replacement = null;
        try (var oldOutput = new java.io.BufferedReader(new java.io.InputStreamReader(old.getInputStream()))) {
            assertEquals("CLAIMED", readMarker(oldOutput));
            jdbc.update("UPDATE mathvision_tasks SET lease_until=NOW(3)-INTERVAL 1 SECOND WHERE id=?", id);
            replacement = child(id, "finish");
            try (var replacementOutput = new java.io.BufferedReader(new java.io.InputStreamReader(replacement.getInputStream()))) {
                assertEquals("COMMITTED", readMarker(replacementOutput));
            }
            assertTrue(replacement.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, replacement.exitValue());
            old.getOutputStream().write('\n'); old.getOutputStream().flush();
            assertEquals("FENCED", readMarker(oldOutput));
            assertTrue(old.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, old.exitValue());
            assertEquals("waiting_confirm", tasks.findById(id).getStatus());
        } finally {
            old.destroyForcibly(); if (replacement != null) replacement.destroyForcibly();
        }
    }
    private Process child(long id, String operation) throws java.io.IOException {
        String executable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        ProcessBuilder builder = new ProcessBuilder(executable, "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                WorkerProcess.class.getName(), Long.toString(id), operation).redirectErrorStream(true);
        builder.environment().put("MV_PROCESS_MYSQL_URL", processUrl); return builder.start();
    }
    private String readMarker(java.io.BufferedReader output) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                for (String line; (line = output.readLine()) != null;)
                    if (Set.of("CLAIMED", "COMMITTED", "FENCED").contains(line)) return line;
                return "EXITED";
            } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
        }).get(15, TimeUnit.SECONDS);
    }
    public static class WorkerProcess {
        public static void main(String[] args) throws Exception {
            var source = new DriverManagerDataSource(System.getenv("MV_PROCESS_MYSQL_URL"),
                    System.getenv().getOrDefault("MV_MYSQL_USER", "root"), System.getenv().getOrDefault("MV_MYSQL_PASSWORD", ""));
            var config = new org.apache.ibatis.session.Configuration(); config.setMapUnderscoreToCamelCase(true);
            config.addMapper(MathVisionTaskMapper.class); config.addMapper(MathVisionVersionMapper.class);
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
            var session = new SqlSessionTemplate(factory.getObject()); var tasks = session.getMapper(MathVisionTaskMapper.class);
            long id = Long.parseLong(args[0]); String token = UUID.randomUUID().toString();
            if (tasks.claimWithLease(id, token, 120, 2) != 1) throw new IllegalStateException("Could not claim task");
            var run = tasks.findById(id);
            var commit = new MathVisionStageCommitService(tasks, new DataSourceTransactionManager(source), session.getMapper(MathVisionVersionMapper.class), new ObjectMapper());
            if (args[1].equals("hold")) { System.out.println("CLAIMED"); System.out.flush(); System.in.read(); }
            try { commit.commit(run, false, () -> tasks.markWaitingConfirm(id, "code_generation")); System.out.println("COMMITTED"); }
            catch (MathVisionLeaseLostException fenced) { System.out.println("FENCED"); }
        }
    }
}
