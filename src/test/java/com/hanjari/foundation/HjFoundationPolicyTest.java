package com.hanjari.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hanjari.testsupport.ConcurrentRunner;
import com.hanjari.testsupport.IndependentTransactions;
import com.hanjari.testsupport.TestDatabaseGuard;
import com.hanjari.testsupport.UnresponsiveServer;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * HJ-002 테스트 기반 정책의 DB 없는 단위 검증.
 * 실제 MySQL 동작은 {@code HjMysqlFoundationIT}에서 검증한다.
 */
class HjFoundationPolicyTest {

    private static final String SECRET = "never-print-this-secret";

    @Test
    void readsOnlyDesignatedEnvironmentNamesAndFailsWhenAnyIsMissing() {
        List<String> requested = new ArrayList<>();
        Map<String, String> complete = Map.of(
                "HANJARI_TEST_DB_URL", "jdbc:mysql://localhost:3306/hanjari_test",
                "HANJARI_TEST_DB_USERNAME", "hanjari_test",
                "HANJARI_TEST_DB_PASSWORD", SECRET,
                "HANJARI_DB_URL", "jdbc:mysql://localhost:3306/hanjari_dev");

        TestDatabaseGuard.Settings settings = TestDatabaseGuard.fromEnvironment(name -> {
            requested.add(name);
            return complete.get(name);
        });

        assertThat(requested).containsExactly(
                "HANJARI_TEST_DB_URL", "HANJARI_TEST_DB_USERNAME", "HANJARI_TEST_DB_PASSWORD");
        assertThat(settings.url()).endsWith("/hanjari_test");
        assertThat(settings.toString()).doesNotContain(SECRET).doesNotContain("jdbc:");

        for (String missing : TestDatabaseGuard.ENV_NAMES) {
            for (String absent : new String[] {null, "", "   "}) {
                assertThatThrownBy(() -> TestDatabaseGuard.fromEnvironment(
                        name -> name.equals(missing) ? absent : complete.get(name)))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(missing)
                        .hasMessageNotContaining(SECRET);
            }
        }
    }

    @Test
    void rejectsEverySchemaExceptDedicatedTestSchema() {
        TestDatabaseGuard.requireTestSchema("hanjari_test");

        for (String schema : new String[] {"hanjari_dev", "HANJARI_TEST", "hanjari_test2", " hanjari_test", "mysql", "", null}) {
            assertThatThrownBy(() -> TestDatabaseGuard.requireTestSchema(schema))
                    .as("schema %s", schema)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Refusing");
        }
    }

    @Test
    void connectionFailureFailsInsteadOfSkippingAndHidesCredentials() {
        TestDatabaseGuard.Settings unreachable = new TestDatabaseGuard.Settings(
                "jdbc:mysql://127.0.0.1:1/hanjari_test?connectTimeout=2000&socketTimeout=2000",
                "hanjari_test",
                SECRET);

        assertThatThrownBy(() -> TestDatabaseGuard.connectedSchema(unreachable))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connection failed")
                .hasMessageNotContaining(SECRET)
                .hasNoCause();
    }

    @Test
    void concurrentRunnerStartsTasksTogetherAndFailsHungTasksWithinTimeout() throws Exception {
        // 모든 작업이 동시에 진행 중이어야만 latch가 0이 된다. 순차 실행이면 timeout으로 실패한다.
        int parties = 4;
        CountDownLatch allRunning = new CountDownLatch(parties);
        List<Callable<Integer>> cooperative = new ArrayList<>();
        for (int i = 0; i < parties; i++) {
            int index = i;
            cooperative.add(() -> {
                allRunning.countDown();
                if (!allRunning.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("tasks did not overlap");
                }
                return index;
            });
        }
        assertThat(ConcurrentRunner.run(Duration.ofSeconds(10), cooperative)).containsExactly(0, 1, 2, 3);

        // timeout 후 abort hook이 먼저 호출되고, 반환 시점에는 hung 작업이 이미 끝나 있어야 한다.
        CountDownLatch neverReleased = new CountDownLatch(1);
        CountDownLatch hungTaskExited = new CountDownLatch(1);
        AtomicInteger abortCalls = new AtomicInteger();
        List<ConcurrentRunner.BarrierTask<Integer>> hung = List.of(gate -> {
            gate.await();
            return 1;
        }, gate -> {
            gate.await();
            try {
                neverReleased.await();
                return 2;
            } finally {
                hungTaskExited.countDown();
            }
        });
        long started = System.nanoTime();
        assertThatThrownBy(() -> ConcurrentRunner.runWithGate(
                Duration.ofMillis(500), Duration.ofSeconds(5), hung, abortCalls::incrementAndGet))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("did not finish within");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(abortCalls).hasValue(1);
        assertThat(hungTaskExited.getCount()).as("hung task must have exited before runner returned").isZero();

        // interrupt를 무시해 grace 안에 끝나지 않는 작업은 격리 실패로 보고해야 한다(조용히 성공 처리하지 않는다).
        AtomicBoolean release = new AtomicBoolean(false);
        try {
            List<ConcurrentRunner.BarrierTask<Integer>> stubborn = List.of(gate -> {
                gate.await();
                while (!release.get()) {
                    Thread.onSpinWait();
                }
                return 1;
            });
            assertThatThrownBy(() -> ConcurrentRunner.runWithGate(
                    Duration.ofMillis(200), Duration.ofMillis(200), stubborn, () -> { }))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("still running")
                    .hasMessageContaining("isolation is not guaranteed")
                    .cause()
                    .hasMessageContaining("did not finish within");
        } finally {
            release.set(true);
        }

        // abort hook 자체가 응답하지 않아도(예: 응답 없는 DB에 KILL) 실행기는 유한 시간 안에 interrupt·종료 대기로 넘어가 실패를 보고한다.
        AtomicBoolean releaseHook = new AtomicBoolean(false);
        CountDownLatch hookHungTaskExited = new CountDownLatch(1);
        try {
            List<ConcurrentRunner.BarrierTask<Integer>> blocked = List.of(gate -> {
                gate.await();
                try {
                    new CountDownLatch(1).await();
                    return 1;
                } finally {
                    hookHungTaskExited.countDown();
                }
            });
            long hookStarted = System.nanoTime();
            assertThatThrownBy(() -> ConcurrentRunner.runWithGate(
                    Duration.ofMillis(300), Duration.ofMillis(300), Duration.ofSeconds(2), blocked, () -> {
                        while (!releaseHook.get()) {
                            Thread.onSpinWait(); // interrupt도 무시하는 정지 상태
                        }
                    }))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("did not finish within")
                    .satisfies(error -> assertThat(error.getSuppressed())
                            .anySatisfy(suppressed -> assertThat(suppressed)
                                    .hasMessageContaining("abort hook did not finish within")));
            assertThat(Duration.ofNanos(System.nanoTime() - hookStarted))
                    .as("timeout 300ms + abortTimeout 300ms + grace 2s 안에 반환")
                    .isLessThan(Duration.ofMillis(2600));
            assertThat(hookHungTaskExited.getCount()).isZero();
        } finally {
            releaseHook.set(true);
        }

        // 실제 JDBC 경로: handshake를 보내지 않는 서버에서 작업 연결이 멈춰도 IndependentTransactions는
        // 무기한 대기하지 않고 유한 시간 안에 격리 실패를 보고한다. 자격 증명은 메시지에 남지 않는다.
        try (UnresponsiveServer server = UnresponsiveServer.start()) {
            TestDatabaseGuard.Settings frozen = new TestDatabaseGuard.Settings(server.jdbcUrl(), "hanjari_test", SECRET);
            IndependentTransactions.AbortPolicy policy = new IndependentTransactions.AbortPolicy(
                    Duration.ofMillis(300), Duration.ofMillis(300), Duration.ofMillis(300),
                    () -> DriverManager.getConnection(server.jdbcUrl(), "unused", "unused"));
            // 드라이버 로딩 시간이 아니라 서버 무응답에서 멈추도록 드라이버를 먼저 올리고, 연결이 서버에 닿을 시간을 준다.
            Class.forName("com.mysql.cj.jdbc.Driver");
            Duration runTimeout = Duration.ofSeconds(2);
            long frozenStarted = System.nanoTime();
            assertThatThrownBy(() -> IndependentTransactions.run(frozen, runTimeout,
                    List.<IndependentTransactions.TransactionWork<Integer>>of(connection -> 1, connection -> 2), policy))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("isolation is not guaranteed")
                    .hasMessageNotContaining(SECRET);
            assertThat(Duration.ofNanos(System.nanoTime() - frozenStarted))
                    .isLessThan(runTimeout.plus(policy.worstCaseAbortDuration()).plusSeconds(1));
            assertThat(server.acceptedConnections()).as("작업 연결 2개가 실제로 응답 없는 서버에 붙었다").isEqualTo(2);
        }

        assertThatThrownBy(() -> ConcurrentRunner.run(Duration.ofSeconds(5),
                List.<Callable<Integer>>of(() -> 1, () -> {
                    throw new IllegalArgumentException("boom");
                })))
                .isInstanceOf(AssertionError.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
