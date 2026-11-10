package com.hanjari.testsupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

/**
 * 후속 FK·동시성 테스트용 독립 트랜잭션 도구(HJ-002 AC4).
 *
 * <p>각 작업은 커넥션 풀을 거치지 않는 별도 JDBC 연결과 트랜잭션에서 실행된다.
 * 모든 연결이 준비되고 트랜잭션이 시작된 뒤 시작 barrier를 함께 통과한다.
 * 작업이 정상 반환하면 commit하고, 예외가 나면 rollback한 뒤 테스트를 실패시킨다.
 * 판정은 반환값이 아니라 호출자가 작업 종료 후 DB를 다시 조회해 수행한다.
 *
 * <p>timeout 또는 다른 작업의 실패로 중단할 때의 보장:
 * <ol>
 *   <li>commit에 들어가지 않은 모든 작업을 ABORTED로 표시한다. 이후 해당 작업은 commit하지 않고 rollback한다.</li>
 *   <li>별도 관리 연결에서 작업 세션마다 {@code KILL <connection_id>}를 실행한다. 서버가 진행 중 문장을 끊고
 *       미커밋 트랜잭션을 rollback한다. 잠금 대기나 SLEEP처럼 interrupt로 멈추지 않는 JDBC 호출도 이것으로 끝낸다.
 *       관리 연결 생성과 KILL 전체는 별도 스레드에서 {@link AbortPolicy#killTimeout()} 안에 끝나야 하며,
 *       관리 연결에는 연결·읽기 timeout도 건다.</li>
 *   <li>KILL의 성공·실패·timeout과 무관하게 클라이언트 쪽 연결을 {@link Connection#abort}로 닫고,
 *       {@link ConcurrentRunner}가 모든 작업 스레드 종료를 유한 grace 동안 기다린다.</li>
 *   <li>반환 전에 {@code information_schema.processlist}에서 작업 세션이 모두 사라졌는지
 *       {@link AbortPolicy#sessionCheckTimeout()} 안에 확인한다. 남아 있거나 확인할 수 없으면(관리 연결 무응답 포함)
 *       격리 실패로 AssertionError를 던진다.</li>
 * </ol>
 * 따라서 실패 경로는 서버·네트워크가 응답하지 않아도 {@code timeout + AbortPolicy#worstCaseAbortDuration()} 안에 반환한다.
 * 중단 결정 이전에 이미 commit을 시작한 작업은 그 commit이 끝날 수 있다. 중단 결정 이후 commit은 일어나지 않는다.
 *
 * <p>InnoDB 잠금 대기 timeout은 전체 timeout보다 조금 길게 둔다. 그래서 실행기 timeout과 KILL이 먼저 적용되고,
 * 잠금 대기 timeout은 KILL마저 실패할 때 쓰는 DB 쪽 유한 상한이 된다.
 */
public final class IndependentTransactions {

    private static final int RUNNING = 0;
    private static final int COMMITTING = 1;
    private static final int ABORTED = 2;
    private static final int FINISHED = 3;

    private static final Duration SESSION_GONE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_KILL_TIMEOUT = Duration.ofSeconds(5);
    /** 관리 연결의 연결·읽기 timeout. KILL·processlist 조회 한 번이 이 시간을 넘지 않는다. */
    private static final Duration ADMIN_IO_TIMEOUT = Duration.ofSeconds(3);
    /** KILL 상한 이후 클라이언트 연결 abort에 남겨 두는 여유. */
    private static final Duration CLIENT_ABORT_MARGIN = Duration.ofSeconds(2);

    private IndependentTransactions() {
    }

    @FunctionalInterface
    public interface TransactionWork<T> {
        T execute(Connection connection) throws Exception;
    }

    private static final class Slot {
        final AtomicInteger state = new AtomicInteger(RUNNING);
        volatile Connection connection;
        volatile long connectionId = -1L;
    }

    /** 관리(KILL·세션 확인)용 연결을 여는 방법. 회귀 테스트에서 응답 없는 서버로 바꿔 끼울 수 있다. */
    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    /**
     * 중단 경로의 유한 상한.
     *
     * @param killTimeout         관리 연결 생성과 모든 KILL의 전체 상한. 넘기면 KILL을 포기하고 클라이언트 연결 정리로 넘어간다.
     * @param terminationGrace    클라이언트 연결 정리 후 작업 스레드 종료를 기다리는 상한
     * @param sessionCheckTimeout 관리 연결 생성과 processlist 확인의 전체 상한
     * @param adminConnections    관리 연결 생성 방법
     */
    public record AbortPolicy(Duration killTimeout, Duration terminationGrace, Duration sessionCheckTimeout,
                              ConnectionFactory adminConnections) {
        /** 실패 경로 전체 상한(실행 timeout 제외). */
        public Duration worstCaseAbortDuration() {
            return killTimeout.plus(CLIENT_ABORT_MARGIN).plus(terminationGrace).plus(sessionCheckTimeout);
        }
    }

    /** 기본 정책: 관리 연결에도 연결·읽기 timeout을 걸고, 각 단계에 유한 상한을 둔다. */
    public static AbortPolicy defaultAbortPolicy(TestDatabaseGuard.Settings settings) {
        return new AbortPolicy(DEFAULT_KILL_TIMEOUT, ConcurrentRunner.DEFAULT_TERMINATION_GRACE, SESSION_GONE_TIMEOUT,
                () -> TestDatabaseGuard.openConnection(settings, ADMIN_IO_TIMEOUT));
    }

    public static <T> List<T> run(TestDatabaseGuard.Settings settings, Duration timeout,
                                  List<? extends TransactionWork<T>> works) {
        return run(settings, timeout, works, defaultAbortPolicy(settings));
    }

    /**
     * 실패 경로는 {@code timeout + policy.worstCaseAbortDuration()} 안에 반환한다.
     * 관리 연결이 응답하지 않아도 클라이언트 연결 abort와 스레드 종료 대기는 수행하며, 세션 소멸을 확인할 수 없으면
     * 격리 실패로 보고한다.
     */
    public static <T> List<T> run(TestDatabaseGuard.Settings settings, Duration timeout,
                                  List<? extends TransactionWork<T>> works, AbortPolicy policy) {
        long lockWaitSeconds = Math.max(1L, (timeout.toMillis() + 999L) / 1000L) + 1L;
        AtomicBoolean aborted = new AtomicBoolean(false);
        List<Slot> slots = new ArrayList<>();
        List<ConcurrentRunner.BarrierTask<T>> tasks = new ArrayList<>();
        for (TransactionWork<T> work : works) {
            Slot slot = new Slot();
            slots.add(slot);
            tasks.add(gate -> runOne(settings, lockWaitSeconds, aborted, slot, gate, work));
        }
        try {
            // hook 상한은 KILL 상한 + 여유. KILL이 멈춰도 hook 안의 클라이언트 연결 abort가 실행될 시간을 남긴다.
            return ConcurrentRunner.runWithGate(timeout, policy.killTimeout().plus(CLIENT_ABORT_MARGIN),
                    policy.terminationGrace(), tasks, () -> abortAll(policy, aborted, slots));
        } catch (AssertionError failure) {
            try {
                ConcurrentRunner.callWithin(policy.sessionCheckTimeout(), "transaction session check", () -> {
                    awaitSessionsGone(policy, slots);
                    return null;
                });
            } catch (Exception | Error verification) {
                AssertionError isolation = new AssertionError(
                        "Transaction sessions still alive or unverifiable after abort; "
                                + "test isolation is not guaranteed", failure);
                isolation.addSuppressed(verification);
                throw isolation;
            }
            throw failure;
        }
    }

    private static <T> T runOne(TestDatabaseGuard.Settings settings, long lockWaitSeconds, AtomicBoolean aborted,
                                Slot slot, ConcurrentRunner.StartGate gate, TransactionWork<T> work) throws Exception {
        try (Connection connection = TestDatabaseGuard.openConnection(settings)) {
            slot.connection = connection;
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT CONNECTION_ID()")) {
                rs.next();
                slot.connectionId = rs.getLong(1);
            }
            // 등록 후 중단 여부를 확인한다. 중단 쪽은 플래그를 먼저 세운 뒤 등록된 연결을 본다. 그래서 둘 중 하나는 반드시 상대를 관찰한다.
            if (aborted.get()) {
                throw new CancellationException("aborted before start");
            }
            TestDatabaseGuard.requireTestSchema(connection.getCatalog());
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET SESSION innodb_lock_wait_timeout = " + lockWaitSeconds);
            }
            connection.setAutoCommit(false);
            try {
                gate.await();
                T result = work.execute(connection);
                if (!slot.state.compareAndSet(RUNNING, COMMITTING)) {
                    throw new CancellationException("aborted before commit");
                }
                connection.commit();
                slot.state.set(FINISHED);
                return result;
            } catch (Exception | Error e) {
                rollbackQuietly(connection, e);
                throw e;
            }
        }
    }

    /**
     * 중단: 상태 전환 → (유한 상한 안에서) 서버 세션 KILL → 클라이언트 연결 abort.
     * 클라이언트 연결 abort는 KILL의 성공·실패·timeout과 관계없이 항상 수행한다.
     */
    private static void abortAll(AbortPolicy policy, AtomicBoolean aborted, List<Slot> slots) {
        aborted.set(true);
        List<Slot> toKill = new ArrayList<>();
        for (Slot slot : slots) {
            // commit 중이거나 끝난 작업은 건드리지 않는다. 그 외 작업은 더 이상 commit할 수 없다.
            if (slot.state.compareAndSet(RUNNING, ABORTED)) {
                toKill.add(slot);
            }
        }
        IllegalStateException problems = new IllegalStateException("Transaction abort was incomplete");
        try {
            ConcurrentRunner.callWithin(policy.killTimeout(), "KILL of transaction sessions", () -> {
                killSessions(policy.adminConnections(), toKill);
                return null;
            });
        } catch (Exception | Error e) {
            problems.addSuppressed(e);
        } finally {
            for (Slot slot : toKill) {
                Connection connection = slot.connection;
                if (connection != null) {
                    try {
                        connection.abort(Runnable::run);
                    } catch (SQLException | RuntimeException e) {
                        problems.addSuppressed(e);
                    }
                }
            }
        }
        if (problems.getSuppressed().length > 0) {
            throw problems;
        }
    }

    private static void killSessions(ConnectionFactory adminConnections, List<Slot> toKill) throws SQLException {
        if (toKill.stream().noneMatch(slot -> slot.connectionId > 0)) {
            return;
        }
        SQLException firstFailure = null;
        try (Connection admin = adminConnections.open()) {
            TestDatabaseGuard.requireTestSchema(admin.getCatalog());
            for (Slot slot : toKill) {
                long id = slot.connectionId;
                if (id <= 0) {
                    continue;
                }
                try (Statement statement = admin.createStatement()) {
                    statement.execute("KILL " + id);
                } catch (SQLException e) {
                    // 이미 끝난 세션이면 'Unknown thread id'(1094). 정상이다.
                    if (e.getErrorCode() != 1094) {
                        firstFailure = firstFailure == null ? e : firstFailure;
                    }
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    /**
     * 작업 세션이 서버에서 모두 사라질 때까지 확인한다(KILL 후 rollback 완료 대기).
     * 호출자가 {@link ConcurrentRunner#callWithin}으로 전체 상한을 건다. 여기서는 interrupt되면 멈춘다.
     */
    private static void awaitSessionsGone(AbortPolicy policy, List<Slot> slots) throws SQLException {
        List<Long> ids = slots.stream().map(slot -> slot.connectionId).filter(id -> id > 0).toList();
        if (ids.isEmpty()) {
            return;
        }
        String sql = "SELECT id FROM information_schema.processlist WHERE id IN ("
                + ids.stream().map(id -> "?").collect(Collectors.joining(",")) + ")";
        long deadline = System.nanoTime() + policy.sessionCheckTimeout().toNanos();
        try (Connection admin = policy.adminConnections().open();
             PreparedStatement query = admin.prepareStatement(sql)) {
            TestDatabaseGuard.requireTestSchema(admin.getCatalog());
            for (int i = 0; i < ids.size(); i++) {
                query.setLong(i + 1, ids.get(i));
            }
            while (true) {
                List<Long> alive = new ArrayList<>();
                try (ResultSet rs = query.executeQuery()) {
                    while (rs.next()) {
                        alive.add(rs.getLong(1));
                    }
                }
                if (alive.isEmpty()) {
                    return;
                }
                if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) {
                    throw new AssertionError("Sessions still present after "
                            + policy.sessionCheckTimeout() + ": " + alive);
                }
                // 조건 확인용 짧은 폴링 간격이다. 정합성 판정에 쓰는 sleep이 아니다.
                LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
            }
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable cause) {
        try {
            if (!connection.isClosed()) {
                connection.rollback();
            }
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }
}
