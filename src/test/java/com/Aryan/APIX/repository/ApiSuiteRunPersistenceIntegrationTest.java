package com.Aryan.APIX.repository;

import com.Aryan.APIX.entity.ApiSuiteRun;
import com.Aryan.APIX.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs only with an explicitly supplied disposable MySQL test database. Never points at the
 * application's production datasource settings.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("persistence-test")
@EnabledIfEnvironmentVariable(named = "APIX_TEST_DATASOURCE_URL", matches = "^jdbc:mysql:.*$", disabledReason = "Set APIX_TEST_DATASOURCE_URL to an isolated MySQL test schema")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApiSuiteRunPersistenceIntegrationTest {
    @Autowired ApiSuiteRunRepository runs;
    @PersistenceContext EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    @Test
    void checkedInMigrationBackfillsLegacyRowsAndPreservesDefaultsAndResults() {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TEMPORARY TABLE api_suite_runs (" +
                            "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, " +
                            "status VARCHAR(24) NOT NULL, passed INT NOT NULL, failed INT NOT NULL, " +
                            "skipped INT NOT NULL, errored INT NOT NULL, results_json LONGTEXT NOT NULL, " +
                            "started_at DATETIME NOT NULL, INDEX idx_api_suite_run_user_started (user_id, started_at)) ENGINE=InnoDB");
                    statement.execute("INSERT INTO api_suite_runs (user_id,status,passed,failed,skipped,errored,results_json,started_at) VALUES " +
                            "(1,'PASSED',3,0,0,0,'[\\\"preserve\\\"]',NOW())," +
                            "(1,'PARTIAL',1,1,0,0,'[]',NOW())," +
                            "(1,'ERROR',0,0,0,2,'[]',NOW())");
                }
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/manual-migrations/20261013_01_api_suite_run_progress.sql"));
                try (Statement statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT id,status,results_json,total_cases,completed_cases,current_case_id,current_case_name,version FROM api_suite_runs ORDER BY id")) {
                    assertTrue(rows.next());
                    assertEquals("PASSED", rows.getString("status"));
                    assertEquals("[\"preserve\"]", rows.getString("results_json"));
                    assertEquals(3, rows.getInt("total_cases"));
                    assertEquals(3, rows.getInt("completed_cases"));
                    assertNull(rows.getString("current_case_id"));
                    assertNull(rows.getString("current_case_name"));
                    assertEquals(0L, rows.getLong("version"));
                    assertTrue(rows.next());
                    assertEquals(2, rows.getInt("total_cases"));
                    assertEquals(2, rows.getInt("completed_cases"));
                    assertTrue(rows.next());
                    assertEquals(2, rows.getInt("total_cases"));
                    assertEquals(2, rows.getInt("completed_cases"));
                    assertFalse(rows.next());
                }
                try (Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO api_suite_runs (user_id,status,passed,failed,skipped,errored,results_json,started_at) VALUES (1,'QUEUED',0,0,0,0,'[]',NOW())");
                    try (var defaults = statement.executeQuery("SELECT total_cases,completed_cases,current_case_id,current_case_name,version FROM api_suite_runs WHERE id=LAST_INSERT_ID()")) {
                        assertTrue(defaults.next());
                        assertEquals(0, defaults.getInt("total_cases"));
                        assertEquals(0, defaults.getInt("completed_cases"));
                        assertNull(defaults.getString("current_case_id"));
                        assertNull(defaults.getString("current_case_name"));
                        assertEquals(0L, defaults.getLong("version"));
                    }
                }
                return null;
            } finally {
                try (Statement cleanup = connection.createStatement()) {
                    cleanup.execute("DROP TEMPORARY TABLE IF EXISTS api_suite_runs");
                }
            }
        });
    }

    @Test
    void versionRepairMigrationBackfillsNullableVersionsBeforeEnforcingNotNull() {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TEMPORARY TABLE api_suite_runs (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, version BIGINT NULL) ENGINE=InnoDB");
                    statement.execute("INSERT INTO api_suite_runs (version) VALUES (NULL),(4)");
                }
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/manual-migrations/20261014_01_api_suite_run_version_not_null.sql"));
                try (Statement statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT version FROM api_suite_runs ORDER BY id")) {
                    assertTrue(rows.next());
                    assertEquals(0L, rows.getLong(1));
                    assertTrue(rows.next());
                    assertEquals(4L, rows.getLong(1));
                    assertFalse(rows.next());
                }
                assertThrows(java.sql.SQLException.class, () -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("INSERT INTO api_suite_runs (version) VALUES (NULL)");
                    }
                });
                return null;
            } finally {
                try (Statement cleanup = connection.createStatement()) {
                    cleanup.execute("DROP TEMPORARY TABLE IF EXISTS api_suite_runs");
                }
            }
        });
    }

    @Test
    void progressPersistsAndConcurrentConditionalClaimsHaveSingleWinner() throws Exception {
        Long id = createRun("QUEUED");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService claimers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = claimers.submit(() -> claimAfterGate(id, ready, start));
            Future<Integer> second = claimers.submit(() -> claimAfterGate(id, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both workers should reach the claim gate");
            start.countDown();
            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS),
                    "Exactly one worker may transition a queued row to RUNNING");
        } finally {
            start.countDown();
            claimers.shutdownNow();
        }
        ApiSuiteRun claimed = runs.findById(id).orElseThrow();
        assertEquals("RUNNING", claimed.getStatus());
        long claimedVersion = claimed.getVersion();

        claimed.setCurrentCaseId("case-1");
        claimed.setCurrentCaseName("first case");
        claimed.setCompletedCases(1);
        claimed.setPassed(1);
        claimed.setResultsJson("[{\"caseId\":\"case-1\",\"status\":\"PASSED\"}]");
        runs.saveAndFlush(claimed);

        ApiSuiteRun progress = runs.findById(id).orElseThrow();
        assertEquals(2, progress.getTotalCases());
        assertEquals(1, progress.getCompletedCases());
        assertEquals("case-1", progress.getCurrentCaseId());
        assertEquals("first case", progress.getCurrentCaseName());
        assertEquals("[{\"caseId\":\"case-1\",\"status\":\"PASSED\"}]", progress.getResultsJson());
        assertTrue(progress.getVersion() > claimedVersion);

        assertEquals(1, runs.requestRunningCancellation(id));
        assertEquals(0, runs.requestRunningCancellation(id), "Only RUNNING can transition to CANCEL_REQUESTED");
        assertEquals("CANCEL_REQUESTED", runs.findById(id).orElseThrow().getStatus());

        Long queuedId = createRun("QUEUED");
        assertEquals(1, runs.cancelQueued(queuedId, LocalDateTime.now()));
        assertEquals(0, runs.claimQueued(queuedId, LocalDateTime.now()));
        assertEquals("CANCELLED", runs.findById(queuedId).orElseThrow().getStatus());
    }

    @Test
    void staleEntityCannotOverwriteAnotherTransaction() {
        Long id = createRun("QUEUED");
        ApiSuiteRun stale = loadDetached(id);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ApiSuiteRun current = runs.findById(id).orElseThrow();
            current.setStatus("ERROR");
            runs.saveAndFlush(current);
        });

        stale.setStatus("PASSED");
        assertThrows(OptimisticLockingFailureException.class, () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> runs.saveAndFlush(stale)));
        assertEquals("ERROR", runs.findById(id).orElseThrow().getStatus());
    }

    @Test
    void cancellationWinsOverAStaleCompletionWrite() {
        Long id = createRun("RUNNING");
        ApiSuiteRun staleCompletion = loadDetached(id);

        assertEquals(1, runs.requestRunningCancellation(id));
        staleCompletion.setStatus("PASSED");
        assertThrows(OptimisticLockingFailureException.class, () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> runs.saveAndFlush(staleCompletion)));
        assertEquals("CANCEL_REQUESTED", runs.findById(id).orElseThrow().getStatus());
    }

    @Test
    void queuedCancellationAndWorkerClaimHaveOnlyOneDatabaseWinner() throws Exception {
        Long id = createRun("QUEUED");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService contenders = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> claim = contenders.submit(() -> transitionAfterGate(ready, start, () -> runs.claimQueued(id, LocalDateTime.now())));
            Future<Integer> cancel = contenders.submit(() -> transitionAfterGate(ready, start, () -> runs.cancelQueued(id, LocalDateTime.now())));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both transitions should reach the start gate");
            start.countDown();
            int claimed = claim.get(10, TimeUnit.SECONDS);
            int cancelled = cancel.get(10, TimeUnit.SECONDS);
            assertEquals(1, claimed + cancelled);
            assertEquals(claimed == 1 ? "RUNNING" : "CANCELLED", runs.findById(id).orElseThrow().getStatus());
        } finally {
            start.countDown();
            contenders.shutdownNow();
        }
    }

    @Test
    void mysqlColumnMappingsMatchTheProgressEntity() {
        var columns = jdbc.query("SELECT column_name, data_type, character_maximum_length, is_nullable " +
                        "FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'api_suite_runs'",
                (resultSet, rowNum) -> new Object[]{resultSet.getString("column_name"), resultSet.getString("data_type"),
                        resultSet.getLong("character_maximum_length"), resultSet.getString("is_nullable")});
        var byName = new java.util.HashMap<String, Object[]>();
        columns.forEach(column -> byName.put((String) column[0], column));
        assertColumn(byName, "total_cases", "int", 0, "NO");
        assertColumn(byName, "completed_cases", "int", 0, "NO");
        assertColumn(byName, "current_case_id", "varchar", 100, "YES");
        assertColumn(byName, "current_case_name", "varchar", 200, "YES");
        assertColumn(byName, "version", "bigint", 0, "NO");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics " +
                "WHERE table_schema = DATABASE() AND table_name = 'api_suite_runs' AND index_name = 'idx_api_suite_run_user_started'", Integer.class));
    }

    @Test
    void failedTerminalWriteCannotPersistASuccessState() {
        Long id = createRun("RUNNING");
        jdbc.execute("CREATE TRIGGER apix_reject_test_success BEFORE UPDATE ON api_suite_runs FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'synthetic persistence failure'");
        try {
            ApiSuiteRun run = runs.findById(id).orElseThrow();
            run.setStatus("PASSED");
            assertThrows(DataAccessException.class, () -> runs.saveAndFlush(run));
            assertEquals("RUNNING", runs.findById(id).orElseThrow().getStatus());
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS apix_reject_test_success");
        }
    }

    private Long createRun(String status) {
        return new TransactionTemplate(transactionManager).execute(statusTx -> {
            User user = new User();
            user.setName("Persistence test");
            user.setEmail("persistence-" + UUID.randomUUID() + "@example.test");
            user.setProvider("test");
            user.setProviderId(UUID.randomUUID().toString());
            entityManager.persist(user);

            ApiSuiteRun run = new ApiSuiteRun();
            run.setUser(user);
            run.setSuiteId(42L);
            run.setSuiteName("persistence fixture");
            run.setStatus(status);
            run.setPassed(0);
            run.setFailed(0);
            run.setSkipped(0);
            run.setErrored(0);
            run.setTotalCases(2);
            run.setCompletedCases(0);
            run.setResultsJson("[]");
            run.setStartedAt(LocalDateTime.now());
            entityManager.persist(run);
            entityManager.flush();
            return run.getId();
        });
    }

    private ApiSuiteRun loadDetached(Long id) {
        return new TransactionTemplate(transactionManager).execute(status -> runs.findById(id).orElseThrow());
    }

    private int claimAfterGate(Long id, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Claim gate timed out");
        return runs.claimQueued(id, LocalDateTime.now());
    }

    private int transitionAfterGate(CountDownLatch ready, CountDownLatch start, SqlTransition transition) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Transition gate timed out");
        return transition.execute();
    }

    @FunctionalInterface
    private interface SqlTransition { int execute() throws Exception; }

    private void assertColumn(java.util.Map<String, Object[]> columns, String name, String type, long length, String nullable) {
        Object[] column = columns.get(name);
        assertNotNull(column, "Missing column " + name);
        assertEquals(type, column[1]);
        if (length > 0) assertEquals(length, column[2]);
        assertEquals(nullable, column[3]);
    }
}
