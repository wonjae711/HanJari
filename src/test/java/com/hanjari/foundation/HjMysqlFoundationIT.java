package com.hanjari.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanjari.testsupport.IndependentTransactions;
import com.hanjari.testsupport.TestDataCleaner;
import com.hanjari.testsupport.TestDatabaseGuard;
import com.hanjari.testsupport.UnresponsiveServer;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HJ-002: 전용 실제 MySQL(hanjari_test) 기반 검증.
 *
 * <p>환경변수 누락·다른 스키마·연결 실패는 {@link DynamicPropertySource} 단계에서 예외로 컨텍스트 생성을 실패시킨다.
 * Flyway와 데이터 변경은 그 확인 이후에만 일어난다. skip·H2 대체는 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HjMysqlFoundationIT {

    private static final Duration CONCURRENCY_TIMEOUT = Duration.ofSeconds(20);

    private static TestDatabaseGuard.Settings settings;
    private static String preMigrationState;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) throws SQLException {
        settings = TestDatabaseGuard.verifiedFromEnvironment();
        recordPreMigrationState();
        TestDatabaseGuard.register(registry, settings);
    }

    @BeforeEach
    void cleanBeforeEachTest() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            TestDataCleaner.cleanAll(connection);
        }
    }

    @Test
    void connectsOnlyToDedicatedSchemaOnCompatibleMysqlAndRecordsVersions() throws SQLException {
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("hanjari_test");
        assertThat(TestDatabaseGuard.connectedSchema(settings)).isEqualTo("hanjari_test");

        String mysqlVersion = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        int[] parsed = parseVersion(mysqlVersion);
        // CHECK 제약은 8.0.16부터 강제된다. 기존 movies 제약과 후속 inventory CHECK의 전제다.
        assertThat(parsed[0] > 8 || (parsed[0] == 8 && (parsed[1] > 0 || parsed[2] >= 16)))
                .as("MySQL %s must be >= 8.0.16", mysqlVersion)
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT engine FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'movies'",
                String.class)).isEqualToIgnoringCase("InnoDB");

        String driverVersion;
        try (Connection connection = dataSource.getConnection()) {
            driverVersion = connection.getMetaData().getDriverVersion();
        }
        System.out.println("[HJ-002] pre-migration state: " + preMigrationState);
        System.out.println("[HJ-002] versions: java=" + System.getProperty("java.version")
                + ", springBoot=" + SpringBootVersion.getVersion()
                + ", hibernate=" + org.hibernate.Version.getVersionString()
                + ", flyway=" + artifactVersion("org.flywaydb", "flyway-core")
                + ", flywayMysql=" + artifactVersion("org.flywaydb", "flyway-mysql")
                + ", mysqlDriver=" + driverVersion
                + ", mysqlServer=" + mysqlVersion);
    }

    @Test
    void firstApplyOnEmptyDedicatedSchemaThenRerunIsNoOp() {
        // 변경 전 전용 스키마를 다시 확인한다. 그 뒤에만 hanjari_test를 비운다(DB 생성·삭제는 하지 않는다).
        TestDatabaseGuard.requireTestSchema(TestDatabaseGuard.connectedSchema(settings));
        Flyway fresh = Flyway.configure()
                .dataSource(settings.url(), settings.username(), settings.password())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        fresh.clean();
        assertThat(tablesInSchema()).as("dedicated schema must be empty before first apply").isEmpty();

        MigrateResult first = fresh.migrate();

        assertThat(first.success).isTrue();
        assertThat(first.initialSchemaVersion).as("no prior schema version").isNull();
        assertThat(first.migrationsExecuted).isEqualTo(fresh.info().applied().length).isPositive();
        assertThat(tablesInSchema()).containsExactlyInAnyOrder("flyway_schema_history", "movies");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1", Integer.class))
                .isEqualTo(1);
        assertAllMigrationsApplied();
        int historyRows = count("flyway_schema_history");
        System.out.println("[HJ-002] first apply on empty schema: executed=" + first.migrationsExecuted
                + ", initialSchemaVersion=" + first.initialSchemaVersion
                + ", targetSchemaVersion=" + first.targetSchemaVersion
                + ", tables=" + tablesInSchema());

        MigrateResult rerun = fresh.migrate();
        MigrateResult rerunFromContext = flyway.migrate();

        assertThat(rerun.migrationsExecuted).isZero();
        assertThat(rerunFromContext.migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(count("flyway_schema_history")).isEqualTo(historyRows);
        assertThat(count("movies")).isZero();
        System.out.println("[HJ-002] rerun after first apply: executed=" + rerun.migrationsExecuted
                + "/" + rerunFromContext.migrationsExecuted + ", historyRows=" + historyRows);

        assertThat(jdbcTemplate.queryForList(
                "SELECT column_name, data_type, is_nullable, character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'movies' ORDER BY ordinal_position"))
                .extracting(row -> row.get("COLUMN_NAME") + ":" + row.get("DATA_TYPE") + ":" + row.get("IS_NULLABLE"))
                .containsExactly("id:bigint:NO", "title:varchar:NO", "runtime_minutes:int:NO", "age_rating:varchar:NO");
        assertThat(jdbcTemplate.queryForList(
                "SELECT constraint_name FROM information_schema.check_constraints WHERE constraint_schema = DATABASE()",
                String.class)).contains("ck_movies_runtime_positive", "ck_movies_age_rating");
        // MySQL 3819(check constraint violated)은 Spring에서 UncategorizedSQLException으로 변환된다. 제약 이름으로 판정한다.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO movies (title, runtime_minutes, age_rating) VALUES ('x', 0, 'ALL')"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_movies_runtime_positive");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO movies (title, runtime_minutes, age_rating) VALUES ('x', 10, 'AGE_19')"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_movies_age_rating");
        assertThat(count("movies")).isZero();
    }

    @Test
    void rerunPreservesExistingMovieDataAndDetailContract() throws Exception {
        String[][] rows = {
                {"한자리 전체관람가", "95", "ALL"},
                {"한자리 12세", "120", "AGE_12"},
                {"한자리 15세", "131", "AGE_15"},
                {"한자리 청소년관람불가", "600", "ADULT"}};
        for (String[] row : rows) {
            jdbcTemplate.update("INSERT INTO movies (title, runtime_minutes, age_rating) VALUES (?, ?, ?)",
                    row[0], Integer.parseInt(row[1]), row[2]);
        }
        List<Map<String, Object>> before = snapshot();
        int historyRows = count("flyway_schema_history");

        MigrateResult rerun = flyway.migrate();

        assertThat(rerun.migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(count("flyway_schema_history")).isEqualTo(historyRows);
        assertThat(snapshot()).isEqualTo(before);

        long maxId = 0;
        for (Map<String, Object> row : before) {
            long id = ((Number) row.get("id")).longValue();
            maxId = Math.max(maxId, id);
            mockMvc.perform(get("/api/v1/movies/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.length()").value(4))
                    .andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.title").value(row.get("title")))
                    .andExpect(jsonPath("$.runtimeMinutes").value(((Number) row.get("runtime_minutes")).intValue()))
                    .andExpect(jsonPath("$.ageRating").value(row.get("age_rating")));
        }
        mockMvc.perform(get("/api/v1/movies/{id}", maxId + 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MOVIE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/movies/01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MOVIE_ID"));

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void independentTransactionsSerializeRowLockedUpdatesAndRollBackFailures() throws SQLException, IOException {
        jdbcTemplate.update("INSERT INTO movies (title, runtime_minutes, age_rating) VALUES ('잠금 시험', 100, 'ALL')");
        long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM movies", Long.class);
        int parties = 4;

        List<IndependentTransactions.TransactionWork<Long>> works = new ArrayList<>();
        for (int i = 0; i < parties; i++) {
            works.add(connection -> {
                long connectionId;
                try (Statement statement = connection.createStatement();
                     ResultSet rs = statement.executeQuery("SELECT CONNECTION_ID()")) {
                    rs.next();
                    connectionId = rs.getLong(1);
                }
                int current;
                try (PreparedStatement lock = connection.prepareStatement(
                        "SELECT runtime_minutes FROM movies WHERE id = ? FOR UPDATE")) {
                    lock.setLong(1, id);
                    try (ResultSet rs = lock.executeQuery()) {
                        rs.next();
                        current = rs.getInt(1);
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE movies SET runtime_minutes = ? WHERE id = ?")) {
                    update.setInt(1, current + 1);
                    update.setLong(2, id);
                    update.executeUpdate();
                }
                return connectionId;
            });
        }

        List<Long> connectionIds = IndependentTransactions.run(settings, CONCURRENCY_TIMEOUT, works);

        assertThat(Set.copyOf(connectionIds)).hasSize(parties);
        // 사후 판정은 실행기 반환값이 아니라 DB 상태로 한다. 잠금이 없으면 갱신 유실로 104보다 작아진다.
        assertThat(runtimeOf(id)).isEqualTo(100 + parties);

        List<IndependentTransactions.TransactionWork<Integer>> failing = List.of(
                connection -> {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE movies SET runtime_minutes = 1 WHERE id = ?")) {
                        update.setLong(1, id);
                        update.executeUpdate();
                    }
                    throw new IllegalStateException("simulated failure after write");
                });
        assertThatThrownBy(() -> IndependentTransactions.run(settings, CONCURRENCY_TIMEOUT, failing))
                .isInstanceOf(AssertionError.class)
                .hasRootCauseMessage("simulated failure after write");
        assertThat(runtimeOf(id)).isEqualTo(100 + parties);

        // timeout: A는 행을 잠그고 쓴 뒤 SLEEP으로 멈추고, B는 같은 행 잠금을 기다린다. 둘 다 interrupt로는 끝나지 않는 JDBC 호출이다.
        // 실행기는 반환 전에 두 세션을 KILL하고 종료를 확인해야 한다. 이후 DB에는 어떤 변경도 남지 않아야 한다.
        List<Long> sessionIds = new CopyOnWriteArrayList<>();
        List<IndependentTransactions.TransactionWork<Integer>> hanging = hangingWorks(id, sessionIds, new CopyOnWriteArrayList<>());
        Duration hangTimeout = Duration.ofSeconds(2);
        long started = System.nanoTime();
        assertThatThrownBy(() -> IndependentTransactions.run(settings, hangTimeout, hanging))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("did not finish within");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(15)); // SLEEP(60)을 기다리지 않았다.

        assertThat(sessionIds).hasSize(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.processlist WHERE id IN (?, ?)", Integer.class,
                sessionIds.get(0), sessionIds.get(1))).as("killed sessions must be gone").isZero();
        assertThat(runtimeOf(id)).as("no write may survive the timeout").isEqualTo(100 + parties);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM movies WHERE title = 'timeout 표식'", Integer.class)).isZero();
        // 잠금도 남지 않았다: 다른 연결이 즉시 같은 행을 갱신할 수 있다.
        assertThat(jdbcTemplate.update("UPDATE movies SET runtime_minutes = runtime_minutes WHERE id = ?", id))
                .isEqualTo(1);
        System.out.println("[HJ-002] timeout abort: elapsed=" + elapsed.toMillis() + "ms, killedSessions="
                + sessionIds.size() + ", runtimeAfter=" + runtimeOf(id));

        // 관리 연결 무응답: KILL·세션 확인용 관리 연결이 handshake 없이 멈추는 서버로 향한다.
        // 기대: 중단 처리 자체가 유한 시간 안에 끝나고, KILL 실패와 무관하게 클라이언트 연결은 abort되며,
        // 세션 소멸을 확인할 수 없으므로 격리 실패로 보고한다(성공·skip으로 숨기지 않는다).
        List<Long> frozenSessionIds = new CopyOnWriteArrayList<>();
        List<Connection> clientConnections = new CopyOnWriteArrayList<>();
        Duration frozenElapsed;
        AssertionError reported;
        try (UnresponsiveServer frozenAdmin = UnresponsiveServer.start()) {
            IndependentTransactions.AbortPolicy policy = new IndependentTransactions.AbortPolicy(
                    Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(1),
                    // 의도적으로 timeout 없는 연결: 상한은 도구의 deadline만으로 보장되어야 한다.
                    () -> DriverManager.getConnection(frozenAdmin.jdbcUrl(), "unused", "unused"));
            long frozenStarted = System.nanoTime();
            Throwable thrown = catchThrowable(() -> IndependentTransactions.run(
                    settings, hangTimeout, hangingWorks(id, frozenSessionIds, clientConnections), policy));
            frozenElapsed = Duration.ofNanos(System.nanoTime() - frozenStarted);
            assertThat(thrown).isInstanceOf(AssertionError.class);
            reported = (AssertionError) thrown;
            assertThat(frozenAdmin.acceptedConnections()).as("관리 연결이 실제로 무응답 서버에 붙었다").isPositive();
            assertThat(frozenElapsed).as("응답 없는 관리 연결에도 유한 시간 안에 반환")
                    .isLessThan(hangTimeout.plus(policy.worstCaseAbortDuration()).plusSeconds(2));
        }
        assertThat(reported).hasMessageContaining("isolation is not guaranteed");
        assertThat(reported).cause().hasMessageContaining("did not finish within");
        assertThat(reported.getCause().getSuppressed()).anySatisfy(abortProblem -> assertThat(abortProblem.getSuppressed())
                .anySatisfy(killTimeout -> assertThat(killTimeout)
                        .hasMessageContaining("KILL of transaction sessions did not finish within")));
        assertThat(clientConnections).hasSize(2);
        for (Connection client : clientConnections) {
            assertThat(client.isClosed()).as("KILL이 실패해도 클라이언트 연결은 abort되어야 한다").isTrue();
        }
        // 정리: 실제 관리 연결로 남은 세션을 끝내고, 어떤 변경도 commit되지 않았음을 DB로 확인한다.
        for (long sessionId : frozenSessionIds) {
            try {
                jdbcTemplate.execute("KILL " + sessionId);
            } catch (DataAccessException alreadyGone) {
                // 1094 Unknown thread id: 클라이언트 연결 종료로 이미 끝난 세션.
            }
        }
        long goneDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.processlist WHERE id IN (?, ?)", Integer.class,
                frozenSessionIds.get(0), frozenSessionIds.get(1)) > 0) {
            assertThat(System.nanoTime()).as("sessions must end after cleanup KILL").isLessThan(goneDeadline);
            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        }
        assertThat(runtimeOf(id)).isEqualTo(100 + parties);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM movies WHERE title = 'timeout 표식'", Integer.class)).isZero();
        System.out.println("[HJ-002] unresponsive admin abort: elapsed=" + frozenElapsed.toMillis()
                + "ms, clientConnectionsClosed=" + clientConnections.size() + ", runtimeAfter=" + runtimeOf(id));
    }

    /** A는 행을 잠그고 쓴 뒤 SLEEP으로 멈추고, B는 같은 행 잠금을 기다린다. */
    private static List<IndependentTransactions.TransactionWork<Integer>> hangingWorks(
            long id, List<Long> sessionIds, List<Connection> connections) {
        CountDownLatch rowLockedByA = new CountDownLatch(1);
        return List.of(
                connection -> {
                    connections.add(connection);
                    sessionIds.add(sessionId(connection));
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE movies SET runtime_minutes = 1 WHERE id = ?");
                         Statement statement = connection.createStatement()) {
                        update.setLong(1, id);
                        update.executeUpdate();
                        statement.executeUpdate(
                                "INSERT INTO movies (title, runtime_minutes, age_rating) VALUES ('timeout 표식', 77, 'ALL')");
                        rowLockedByA.countDown();
                        statement.executeQuery("SELECT SLEEP(60)").close();
                    }
                    return 1;
                },
                connection -> {
                    connections.add(connection);
                    sessionIds.add(sessionId(connection));
                    // A가 행을 잠근 뒤에만 잠금을 시도한다. 그래야 B가 먼저 commit하는 경로가 없다.
                    if (!rowLockedByA.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("A never locked the row");
                    }
                    try (PreparedStatement lock = connection.prepareStatement(
                            "SELECT runtime_minutes FROM movies WHERE id = ? FOR UPDATE");
                         PreparedStatement update = connection.prepareStatement(
                                 "UPDATE movies SET runtime_minutes = 2 WHERE id = ?")) {
                        lock.setLong(1, id);
                        lock.executeQuery().close();
                        update.setLong(1, id);
                        update.executeUpdate();
                    }
                    return 2;
                });
    }

    private static long sessionId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT CONNECTION_ID()")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void cleanupRuleCoversEveryTableAndPreservesMigrationHistory() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(TestDataCleaner.unregisteredTables(connection))
                    .as("새 테이블은 TestDataCleaner.TABLES_CHILD_FIRST에 FK 자식→부모 순서로 등록해야 한다")
                    .isEmpty();
        }
        jdbcTemplate.update("INSERT INTO movies (title, runtime_minutes, age_rating) VALUES ('정리 대상', 90, 'ALL')");
        int historyRows = count("flyway_schema_history");
        assertThat(count("movies")).isEqualTo(1);

        try (Connection connection = dataSource.getConnection()) {
            TestDataCleaner.cleanAll(connection);
        }

        for (String table : TestDataCleaner.TABLES_CHILD_FIRST) {
            assertThat(count(table)).as(table).isZero();
        }
        assertThat(count("flyway_schema_history")).isEqualTo(historyRows).isPositive();
        assertAllMigrationsApplied();
    }

    private void assertAllMigrationsApplied() {
        MigrationInfo[] all = flyway.info().all();
        assertThat(all).isNotEmpty();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(all).allSatisfy(info -> assertThat(info.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(all).extracting(info -> info.getVersion().getVersion()).contains("1").doesNotContain("1.1");
    }

    private List<String> tablesInSchema() {
        return jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name",
                String.class);
    }

    private List<Map<String, Object>> snapshot() {
        return jdbcTemplate.queryForList("SELECT id, title, runtime_minutes, age_rating FROM movies ORDER BY id");
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Integer.class);
    }

    private int runtimeOf(long id) {
        return jdbcTemplate.queryForObject("SELECT runtime_minutes FROM movies WHERE id = ?", Integer.class, id);
    }

    private static String artifactVersion(String groupId, String artifactId) {
        String path = "META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties";
        try (InputStream in = HjMysqlFoundationIT.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return "unknown";
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static int[] parseVersion(String version) {
        String[] parts = version.split("[^0-9]+");
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
    }

    private static void recordPreMigrationState() throws SQLException {
        try (Connection connection = TestDatabaseGuard.openConnection(settings);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name")) {
            List<String> tables = new ArrayList<>();
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
            preMigrationState = tables.isEmpty() ? "empty schema (fresh Flyway apply in this run)" : "tables=" + tables;
        }
    }
}
