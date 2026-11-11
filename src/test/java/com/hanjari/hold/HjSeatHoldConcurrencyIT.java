package com.hanjari.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hanjari.HanjariApplication;
import com.hanjari.auth.SessionCookieConfig;
import com.hanjari.common.DatabaseTime;
import com.hanjari.common.JdbcDatabaseTime;
import com.hanjari.idempotency.IdempotencyKey;
import com.hanjari.idempotency.IdempotentResponse;
import com.hanjari.screening.ScreeningSchedule;
import com.hanjari.testsupport.ConcurrentRunner;
import com.hanjari.testsupport.IndependentTransactions;
import com.hanjari.testsupport.TestDataCleaner;
import com.hanjari.testsupport.TestDatabaseGuard;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * HJ-006: 실제 MySQL(hanjari_test)과 실제 HTTP 서버로 좌석 확보·해제·만료를 검증한다.
 *
 * <ul>
 *   <li>판정은 응답 수가 아니라 요청 후 DB 상태(holds·hold_items·screening_seats·screenings)로 한다.</li>
 *   <li>동시성: 별도 JDBC 연결이 회원·회차 행 잠금을 쥔 상태에서 경쟁 요청을 시작 barrier로 함께 보내고,
 *       모든 요청이 실제로 그 잠금을 기다리는 것을 processlist로 확인한 뒤 잠금을 푼다(임의 sleep으로 순서를 가정하지 않는다).
 *       각 요청은 독립 연결·트랜잭션이며 전체는 유한 timeout({@link ConcurrentRunner}) 안에 끝나야 한다.</li>
 *   <li>경계 시각: 판정 시각 빈({@link DatabaseTime})을 {@link PinnableDatabaseTime}으로 바꿔 now == expiresAt,
 *       now == startsAt을 마이크로초 단위로 재현한다. 그 밖의 시나리오는 실제 DB 시각과 과거로 옮긴 fixture를 쓴다.</li>
 *   <li>만료 작업자는 테스트 기본값(config/application.properties)으로 꺼져 있다. 작업자 중단 상황은 그대로 재현되고,
 *       작업자 동작은 이 컨텍스트에서만 켜서 확인한다.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "hanjari.security.allowed-origins=http://localhost:5173")
class HjSeatHoldConcurrencyIT {

    private static final String ORIGIN = "http://localhost:5173";
    private static final String COOKIE = SessionCookieConfig.COOKIE_NAME;
    private static final String PASSWORD = "seat hold password 1";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(100);
    private static final Duration CONCURRENCY_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration WAITER_GATE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration WORKER_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration MICRO = Duration.ofNanos(1_000);
    private static final Instant FAR_START = Instant.parse("2031-07-01T01:00:00Z");
    /** HoldService의 잠금 문장. 대기 세션을 processlist에서 찾는 데 쓴다. */
    private static final String MEMBER_LOCK_WAIT = "SELECT id FROM members WHERE id = % FOR UPDATE";
    private static final String SCREENING_LOCK_WAIT =
            "SELECT id, starts_at, base_price FROM screenings WHERE id = % FOR UPDATE";

    private static TestDatabaseGuard.Settings settings;

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        settings = TestDatabaseGuard.verifiedFromEnvironment();
        TestDatabaseGuard.register(registry, settings);
    }

    /** 실제 DB 시각을 기본으로 하되, 고정하면 그 값을 판정 시각으로 쓴다. */
    static final class PinnableDatabaseTime implements DatabaseTime {
        private final DatabaseTime real;
        private final AtomicReference<Instant> pinned = new AtomicReference<>();

        PinnableDatabaseTime(DatabaseTime real) {
            this.real = real;
        }

        void pin(Instant instant) {
            pinned.set(instant);
        }

        void unpin() {
            pinned.set(null);
        }

        @Override
        public Instant now() {
            Instant fixed = pinned.get();
            return fixed != null ? fixed : real.now();
        }
    }

    @TestConfiguration
    static class TimeConfig {
        @Bean
        @Primary
        PinnableDatabaseTime pinnableDatabaseTime(JdbcTemplate jdbcTemplate) {
            return new PinnableDatabaseTime(new JdbcDatabaseTime(jdbcTemplate));
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PinnableDatabaseTime time;

    @Autowired
    private HoldService holdService;

    @Autowired
    private HoldExpiryWorker worker;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void cleanBefore() throws SQLException {
        time.unpin();
        worker.setEnabled(false);
        clean();
    }

    /** 다른 IT의 일괄 삭제가 FK에 막히지 않도록 끝난 뒤에도 비운다. 작업자·시각 고정도 되돌린다. */
    @AfterEach
    void cleanAfter() throws SQLException {
        worker.setEnabled(false);
        time.unpin();
        clean();
    }

    // AC1 수량·원자성 ---------------------------------------------------------------------------------------

    @Test
    void oneAndFourSeatHoldsSucceedWithServerPriceExpiryAndASingleSalesLock() throws Exception {
        long aliceId = signup("hold_one");
        long bobId = signup("hold_four");
        Browser alice = login("hold_one");
        Browser bob = login("hold_four");
        Screening s = screening("확보관", 6, FAR_START, 12000);
        assertThat(salesLockedAt(s.id)).isNull();

        Instant before = dbNow();
        Reply one = alice.post("/api/v1/holds", holdBody(s.id, s.seat(2)), key());
        assertThat(one.status()).isEqualTo(201);
        assertThat(one.keys("$")).containsExactly("holdId", "status", "seatIds", "expiresAt", "totalAmount", "serverNow");
        assertThat((String) one.json("$.status")).isEqualTo("ACTIVE");
        assertThat(one.longs("$.seatIds")).containsExactly(s.seat(2));
        assertThat(((Number) one.json("$.totalAmount")).longValue()).isEqualTo(12000);
        Instant serverNow = Instant.parse((String) one.json("$.serverNow"));
        assertThat(serverNow).isBetween(before, dbNow());
        assertThat(Instant.parse((String) one.json("$.expiresAt"))).isEqualTo(serverNow.plus(Duration.ofMinutes(5)));
        LocalDateTime lockedAt = salesLockedAt(s.id);
        assertThat(ScreeningSchedule.fromDb(lockedAt)).as("first hold records the sales lock").isEqualTo(serverNow);

        Reply four = bob.post("/api/v1/holds", holdBody(s.id, s.seat(5), s.seat(0), s.seat(4), s.seat(1)), key());
        assertThat(four.status()).isEqualTo(201);
        assertThat(four.longs("$.seatIds")).containsExactly(s.seat(0), s.seat(1), s.seat(4), s.seat(5));
        assertThat(((Number) four.json("$.totalAmount")).longValue()).isEqualTo(48000);
        assertThat(salesLockedAt(s.id)).as("later holds keep the first lock time").isEqualTo(lockedAt);

        // DB: 확보 2건, 확보 좌석·단가, inventory 소유가 응답과 일치한다.
        long aliceHold = ((Number) one.json("$.holdId")).longValue();
        long bobHold = ((Number) four.json("$.holdId")).longValue();
        assertThat(jdbcTemplate.queryForList("SELECT id, member_id, status, total_amount FROM holds ORDER BY id"))
                .extracting(r -> r.get("id") + ":" + r.get("member_id") + ":" + r.get("status") + ":" + r.get("total_amount"))
                .containsExactly(aliceHold + ":" + aliceId + ":ACTIVE:12000", bobHold + ":" + bobId + ":ACTIVE:48000");
        assertThat(jdbcTemplate.queryForList("SELECT hold_id, seat_id, unit_price FROM hold_items ORDER BY hold_id, seat_id"))
                .extracting(r -> r.get("hold_id") + ":" + r.get("seat_id") + ":" + r.get("unit_price"))
                .containsExactly(aliceHold + ":" + s.seat(2) + ":12000",
                        bobHold + ":" + s.seat(0) + ":12000", bobHold + ":" + s.seat(1) + ":12000",
                        bobHold + ":" + s.seat(4) + ":12000", bobHold + ":" + s.seat(5) + ":12000");
        assertThat(inventory(s.id)).containsExactly(
                "HELD:" + bobHold, "HELD:" + bobHold, "HELD:" + aliceHold, "AVAILABLE:null", "HELD:" + bobHold,
                "HELD:" + bobHold);

        // 좌석 조회: 본인 좌석만 mine, 본인 만료 시각만 표시. 비회원에게는 상태만.
        Reply aliceView = alice.get("/api/v1/screenings/" + s.id + "/seats");
        assertThat(JsonPath.<List<String>>read(aliceView.body(), "$.seats[*].status"))
                .containsExactly("HELD", "HELD", "HELD", "AVAILABLE", "HELD", "HELD");
        assertThat(JsonPath.<List<Boolean>>read(aliceView.body(), "$.seats[*].mine"))
                .containsExactly(false, false, true, false, false, false);
        assertThat((String) aliceView.json("$.expiresAt")).isEqualTo(one.json("$.expiresAt"));
        Reply anonymousView = new Browser().get("/api/v1/screenings/" + s.id + "/seats");
        assertThat(anonymousView.keys("$")).containsExactlyInAnyOrder("screeningId", "serverNow", "seats");
        assertThat(JsonPath.<List<Boolean>>read(anonymousView.body(), "$.seats[*].mine")).containsOnly(false);
        assertThat(anonymousView.body()).doesNotContain("holdId").doesNotContain("hold_four").doesNotContain("memberId");
        assertThat(((Number) alice.get("/api/v1/holds/current").json("$.holdId")).longValue()).isEqualTo(aliceHold);
    }

    @Test
    void zeroFiveDuplicateAndMalformedSeatRequestsAreRejectedWithoutAnySideEffect() throws Exception {
        signup("hold_reject");
        Browser member = login("hold_reject");
        Screening s = screening("거부관", 6, FAR_START, 9000);
        String before = snapshot();

        Map<String, String> cases = new java.util.LinkedHashMap<>();
        cases.put(holdBody(s.id), "INVALID_SEAT_COUNT");
        cases.put(holdBody(s.id, s.seat(0), s.seat(1), s.seat(2), s.seat(3), s.seat(4)), "INVALID_SEAT_COUNT");
        cases.put(holdBody(s.id, s.seat(0), s.seat(1), s.seat(0)), "DUPLICATE_SEAT_ID");
        cases.put(holdBody(s.id, s.seat(3), s.seat(3)), "DUPLICATE_SEAT_ID");
        cases.put("{\"screeningId\":" + s.id + ",\"seatIds\":[\"" + s.seat(0) + "\"]}", "INVALID_SEAT_IDS");
        cases.put("{\"screeningId\":" + s.id + ",\"seatIds\":[" + s.seat(0) + "],\"totalAmount\":1}", "UNSUPPORTED_FIELD");
        cases.put("{\"seatIds\":[" + s.seat(0) + "]}", "INVALID_SCREENING_ID");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            Reply rejected = member.post("/api/v1/holds", c.getKey(), key());
            assertThat(rejected.status()).as(c.getKey()).isEqualTo(400);
            assertThat(rejected.keys("$")).containsExactlyInAnyOrder("code", "message");
            assertThat((String) rejected.json("$.code")).as(c.getKey()).isEqualTo(c.getValue());
        }
        Reply noKey = member.post("/api/v1/holds", holdBody(s.id, s.seat(0)), null);
        assertThat((String) noKey.json("$.code")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        Browser anonymous = new Browser();
        anonymous.fetchCsrf();
        assertThat(anonymous.post("/api/v1/holds", holdBody(s.id, s.seat(0)), key()).status()).isEqualTo(401);
        assertThat(member.get("/api/v1/holds/abc").status()).isEqualTo(400);
        assertThat(snapshot()).as("no hold, item, seat, sales lock or idempotency record").isEqualTo(before);
        assertThat(salesLockedAt(s.id)).isNull();
    }

    @Test
    void anyForeignMissingOrOccupiedSeatFailsTheWholeRequest() throws Exception {
        long aliceId = signup("hold_atomic");
        signup("hold_blocker");
        Browser alice = login("hold_atomic");
        Browser blocker = login("hold_blocker");
        Screening s1 = screening("원자 1관", 4, FAR_START, 10000);
        Screening s2 = screening("원자 2관", 2, FAR_START, 10000);
        Screening started = screening("원자 지난관", 2, dbNow().minus(Duration.ofMinutes(1)), 10000);
        assertThat(blocker.post("/api/v1/holds", holdBody(s1.id, s1.seat(3)), key()).status()).isEqualTo(201);
        jdbcTemplate.update("UPDATE screening_seats SET state = 'BOOKED' WHERE screening_id = ? AND seat_id = ?",
                s1.id, s1.seat(2));
        String before = snapshot();

        record Case(String body, int status, String code) {
        }
        List<Case> cases = List.of(
                new Case(holdBody(s1.id, s1.seat(0), s2.seat(0)), 400, "SEAT_NOT_IN_SCREENING"),
                new Case(holdBody(s1.id, s1.seat(0), 9_000_000_000L), 400, "SEAT_NOT_IN_SCREENING"),
                new Case(holdBody(s1.id, s1.seat(0), s1.seat(1), s1.seat(3)), 409, "SEAT_UNAVAILABLE"),
                new Case(holdBody(s1.id, s1.seat(2), s1.seat(0)), 409, "SEAT_UNAVAILABLE"),
                new Case(holdBody(9_000_000_000L, s1.seat(0)), 404, "SCREENING_NOT_FOUND"),
                new Case(holdBody(started.id, started.seat(0)), 409, "SCREENING_NOT_BOOKABLE"));
        for (Case c : cases) {
            Reply reply = alice.post("/api/v1/holds", c.body(), key());
            assertThat(reply.status()).as(c.body()).isEqualTo(c.status());
            assertThat((String) reply.json("$.code")).as(c.body()).isEqualTo(c.code());
        }
        // 전체 실패: 유효한 좌석(seat0·seat1)도 확보되지 않았고 확보·판매 잠금·멱등 기록이 없다.
        assertThat(snapshot()).isEqualTo(before);
        assertThat(activeHolds(aliceId)).isZero();
        assertThat(salesLockedAt(s2.id)).isNull();
        assertThat(salesLockedAt(started.id)).isNull();
        // 같은 유효 좌석만 다시 요청하면 성공한다(앞선 실패가 아무것도 남기지 않았다).
        Reply ok = alice.post("/api/v1/holds", holdBody(s1.id, s1.seat(1), s1.seat(0)), key());
        assertThat(ok.status()).isEqualTo(201);
        long holdId = ((Number) ok.json("$.holdId")).longValue();
        assertThat(inventory(s1.id).subList(0, 2)).containsOnly("HELD:" + holdId);
    }

    // AC2 동시 확보 ------------------------------------------------------------------------------------------

    @Test
    void concurrentHoldsOnTheSameSeatLeaveAtMostOneOwnerAndLosersChangeNothing() throws Exception {
        int contenders = 6;
        Screening s = screening("경합관", contenders + 1, FAR_START, 8000);
        List<Browser> members = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            signup("race_seat_" + i);
            members.add(login("race_seat_" + i));
        }
        // 모두 같은 좌석(seat0)과 서로 다른 좌석 하나를 함께 요청한다. 패자는 자기 단독 좌석도 확보하지 못해야 한다.
        List<Callable<Object>> requests = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            Browser browser = members.get(i);
            String body = holdBody(s.id, s.seat(i + 1), s.seat(0));
            requests.add(() -> browser.post("/api/v1/holds", body, key()));
        }
        List<Object> replies = raceBehindLock("SELECT id FROM screenings WHERE id = ? FOR UPDATE", s.id,
                SCREENING_LOCK_WAIT, requests);

        List<Reply> results = replies.stream().map(Reply.class::cast).toList();
        System.out.println("[HJ-006] same seat race: statuses=" + results.stream().map(Reply::status).toList());
        assertThat(results).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() != 201)
                .allSatisfy(r -> {
                    assertThat(r.status()).isEqualTo(409);
                    assertThat((String) r.json("$.code")).isEqualTo("SEAT_UNAVAILABLE");
                });
        Reply winner = results.stream().filter(r -> r.status() == 201).findFirst().orElseThrow();
        long winnerHold = ((Number) winner.json("$.holdId")).longValue();
        // DB 판정: 유효 확보 1건, 경합 좌석의 소유 확보는 승자 하나, 승자 좌석 외에는 모두 AVAILABLE.
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM holds", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hold_items WHERE seat_id = ?", Integer.class,
                s.seat(0))).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT hold_id FROM screening_seats WHERE screening_id = ? AND seat_id = ?",
                Long.class, s.id, s.seat(0))).isEqualTo(winnerHold);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM screening_seats WHERE screening_id = ? "
                + "AND state = 'HELD'", Integer.class, s.id)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM screening_seats WHERE screening_id = ? "
                + "AND state = 'HELD' AND hold_id <> ?", Integer.class, s.id, winnerHold)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idempotency_records", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentHoldsBySameMemberOnDifferentScreeningsLeaveExactlyOneActiveHold() throws Exception {
        long memberId = signup("race_member");
        Browser member = login("race_member");
        int screenings = 4;
        List<Screening> targets = new ArrayList<>();
        for (int i = 0; i < screenings; i++) {
            targets.add(screening("회원 경합 " + i + "관", 2, FAR_START, 7000));
        }
        List<Callable<Object>> requests = new ArrayList<>();
        for (Screening target : targets) {
            Browser copy = member.copy();
            requests.add(() -> copy.post("/api/v1/holds", holdBody(target.id, target.seat(0)), key()));
        }
        List<Reply> results = raceBehindLock("SELECT id FROM members WHERE id = ? FOR UPDATE", memberId,
                MEMBER_LOCK_WAIT, requests).stream().map(Reply.class::cast).toList();

        System.out.println("[HJ-006] same member race: statuses=" + results.stream().map(Reply::status).toList());
        assertThat(results).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() != 201).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(409);
            assertThat((String) r.json("$.code")).isEqualTo("ACTIVE_HOLD_EXISTS");
        });
        Reply winner = results.stream().filter(r -> r.status() == 201).findFirst().orElseThrow();
        assertThat(activeHolds(memberId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM holds", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM screening_seats WHERE state = 'HELD'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM screenings WHERE sales_locked_at IS NOT NULL",
                Integer.class)).as("only the winner's screening is sales-locked").isEqualTo(1);

        // 해제한 뒤에는 다른 회차를 확보할 수 있고, 여전히 유효 확보는 1건이다.
        long winnerHold = ((Number) winner.json("$.holdId")).longValue();
        assertThat(member.post("/api/v1/holds/" + winnerHold + "/release", null, key()).status()).isEqualTo(200);
        long winnerScreening = ((Number) member.get("/api/v1/holds/" + winnerHold).json("$.screeningId")).longValue();
        Screening other = targets.stream().filter(t -> t.id != winnerScreening).findFirst().orElseThrow();
        assertThat(member.post("/api/v1/holds", holdBody(other.id, other.seat(1)), key()).status()).isEqualTo(201);
        assertThat(activeHolds(memberId)).isEqualTo(1);
    }

    @Test
    void independentTransactionsCannotGiveOneSeatTwoOwnersAndDbConstraintsGuardReferences() throws Exception {
        long aliceId = signup("guard_alice");
        long bobId = signup("guard_bob");
        Screening s = screening("제약관", 2, FAR_START, 5000);
        Screening other = screening("제약 옆관", 1, FAR_START, 5000);
        long holdA = fixtureHold(aliceId, s.id);
        long holdB = fixtureHold(bobId, s.id);
        long otherHold = fixtureHold(bobId, other.id);

        // 두 독립 트랜잭션이 같은 좌석을 조건부로 차지하려 한다. 행 잠금으로 직렬화되어 정확히 한쪽만 반영된다.
        List<IndependentTransactions.TransactionWork<Integer>> works = new ArrayList<>();
        for (long hold : List.of(holdA, holdB)) {
            works.add(connection -> {
                try (PreparedStatement claim = connection.prepareStatement("UPDATE screening_seats "
                        + "SET state = 'HELD', hold_id = ? WHERE screening_id = ? AND seat_id = ? AND state = 'AVAILABLE'")) {
                    claim.setLong(1, hold);
                    claim.setLong(2, s.id);
                    claim.setLong(3, s.seat(0));
                    return claim.executeUpdate();
                }
            });
        }
        List<Integer> updated = IndependentTransactions.run(settings, CONCURRENCY_TIMEOUT, works);
        assertThat(updated).containsExactlyInAnyOrder(0, 1);
        assertThat(jdbcTemplate.queryForObject("SELECT hold_id FROM screening_seats WHERE screening_id = ? AND seat_id = ?",
                Long.class, s.id, s.seat(0))).isIn(holdA, holdB);

        // inventory 상태·참조 조합, 회차 일치, 확보 시간 창, 상태 값은 DB가 거부한다.
        List<Object[]> rejected = List.of(
                new Object[] {"UPDATE screening_seats SET state = 'HELD', hold_id = NULL WHERE screening_id = ? AND seat_id = ?",
                        s.id, s.seat(1)},
                new Object[] {"UPDATE screening_seats SET state = 'AVAILABLE' WHERE screening_id = ? AND seat_id = ?",
                        s.id, s.seat(0)},
                new Object[] {"UPDATE screening_seats SET state = 'BOOKED' WHERE screening_id = ? AND seat_id = ?",
                        s.id, s.seat(0)},
                new Object[] {"UPDATE screening_seats SET state = 'HELD', hold_id = ? WHERE screening_id = ? AND seat_id = ?",
                        otherHold, s.id, s.seat(1)},
                new Object[] {"INSERT INTO hold_items (hold_id, screening_id, seat_id, unit_price) VALUES (?, ?, ?, 5000)",
                        holdA, s.id, other.seat(0)},
                new Object[] {"INSERT INTO hold_items (hold_id, screening_id, seat_id, unit_price) VALUES (?, ?, ?, 5000)",
                        otherHold, s.id, s.seat(1)},
                new Object[] {"UPDATE holds SET expires_at = DATE_ADD(created_at, INTERVAL 301 SECOND) WHERE id = ?", holdA},
                new Object[] {"UPDATE holds SET expires_at = created_at WHERE id = ?", holdA},
                new Object[] {"UPDATE holds SET status = 'LOST' WHERE id = ?", holdA},
                new Object[] {"UPDATE holds SET status = 'RELEASED' WHERE id = ?", holdA},
                new Object[] {"UPDATE holds SET ended_at = UTC_TIMESTAMP(6) WHERE id = ?", holdA},
                new Object[] {"DELETE FROM holds WHERE id = ?", fixtureOwnerOf(s.id, s.seat(0))});
        String before = snapshot();
        for (Object[] statement : rejected) {
            Object[] args = java.util.Arrays.copyOfRange(statement, 1, statement.length);
            assertThatThrownBy(() -> jdbcTemplate.update((String) statement[0], args)).as((String) statement[0])
                    .isInstanceOf(DataAccessException.class);
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    // AC3 만료 경계·연장 없음 -------------------------------------------------------------------------------

    @Test
    void holdExpiresExactlyFiveMinutesAfterCreationAndTheSeatBecomesAvailableAtThatInstant() throws Exception {
        long aliceId = signup("expiry_alice");
        signup("expiry_bob");
        Browser alice = login("expiry_alice");
        Browser bob = login("expiry_bob");
        Instant p = dbNow();
        Screening s = screening("만료관", 2, p.plus(Duration.ofDays(1)), 11000);
        time.pin(p);
        Reply held = alice.post("/api/v1/holds", holdBody(s.id, s.seat(0)), key());
        assertThat(held.status()).isEqualTo(201);
        long holdId = ((Number) held.json("$.holdId")).longValue();
        Instant expiresAt = p.plus(Duration.ofMinutes(5));
        assertThat(Instant.parse((String) held.json("$.expiresAt"))).isEqualTo(expiresAt);
        assertThat(Instant.parse((String) held.json("$.serverNow"))).isEqualTo(p);
        assertThat(holdTime(holdId, "created_at")).isEqualTo(ScreeningSchedule.toDb(p));
        assertThat(holdTime(holdId, "expires_at")).isEqualTo(ScreeningSchedule.toDb(expiresAt));

        // 만료 1µs 전: 유효. 다른 회원은 확보할 수 없다.
        time.pin(expiresAt.minus(MICRO));
        assertThat((String) alice.get("/api/v1/holds/" + holdId).json("$.status")).isEqualTo("ACTIVE");
        assertThat(alice.get("/api/v1/holds/current").status()).isEqualTo(200);
        Reply aliceSeats = alice.get("/api/v1/screenings/" + s.id + "/seats");
        assertThat((String) aliceSeats.json("$.seats[0].status")).isEqualTo("HELD");
        assertThat((Boolean) aliceSeats.json("$.seats[0].mine")).isTrue();
        Reply blocked = bob.post("/api/v1/holds", holdBody(s.id, s.seat(0)), key());
        assertThat(blocked.status()).isEqualTo(409);
        assertThat((String) blocked.json("$.code")).isEqualTo("SEAT_UNAVAILABLE");

        // 정확히 만료 시각: 만료(조회는 DB를 바꾸지 않는다). 좌석은 다른 회원이 확보할 수 있다.
        time.pin(expiresAt);
        assertThat((String) alice.get("/api/v1/holds/" + holdId).json("$.status")).isEqualTo("EXPIRED");
        assertThat(alice.get("/api/v1/holds/current").status()).isEqualTo(204);
        Reply expiredSeats = alice.get("/api/v1/screenings/" + s.id + "/seats");
        assertThat((String) expiredSeats.json("$.seats[0].status")).isEqualTo("AVAILABLE");
        assertThat(expiredSeats.keys("$")).doesNotContain("expiresAt");
        assertThat(holdStatus(holdId)).as("reads do not write").isEqualTo("ACTIVE");
        Reply taken = bob.post("/api/v1/holds", holdBody(s.id, s.seat(0)), key());
        assertThat(taken.status()).isEqualTo(201);
        assertThat(Instant.parse((String) taken.json("$.expiresAt"))).isEqualTo(expiresAt.plus(Duration.ofMinutes(5)));
        assertThat(holdStatus(holdId)).isEqualTo("EXPIRED");
        assertThat(holdTime(holdId, "ended_at")).isEqualTo(ScreeningSchedule.toDb(expiresAt));
        assertThat(inventory(s.id).get(0)).isEqualTo("HELD:" + ((Number) taken.json("$.holdId")).longValue());
        assertThat(activeHolds(aliceId)).isZero();
    }

    @Test
    void readsResendsAndRepeatedRequestsNeverExtendTheHold() throws Exception {
        signup("extend_alice");
        Browser alice = login("extend_alice");
        Instant p = dbNow();
        Screening s = screening("연장관", 3, p.plus(Duration.ofDays(1)), 10000);
        Screening other = screening("연장 옆관", 1, p.plus(Duration.ofDays(1)), 10000);
        String createKey = key();
        String body = holdBody(s.id, s.seat(0), s.seat(1));
        time.pin(p);
        Reply original = alice.post("/api/v1/holds", body, createKey);
        long holdId = ((Number) original.json("$.holdId")).longValue();
        Instant expiresAt = p.plus(Duration.ofMinutes(5));
        LocalDateTime stored = jdbcTemplate.queryForObject("SELECT expires_at FROM holds WHERE id = ?",
                LocalDateTime.class, holdId);

        for (Duration elapsed : List.of(Duration.ofMinutes(1), Duration.ofMinutes(4), Duration.ofMinutes(5).minus(MICRO))) {
            time.pin(p.plus(elapsed));
            assertThat(Instant.parse((String) alice.get("/api/v1/holds/" + holdId).json("$.expiresAt"))).isEqualTo(expiresAt);
            assertThat(Instant.parse((String) alice.get("/api/v1/holds/current").json("$.expiresAt"))).isEqualTo(expiresAt);
            assertThat(Instant.parse((String) alice.get("/api/v1/screenings/" + s.id + "/seats").json("$.expiresAt")))
                    .isEqualTo(expiresAt);
            Reply resend = alice.post("/api/v1/holds", body, createKey);
            assertThat(resend.status()).isEqualTo(201);
            assertThat(resend.body()).as("resend replays the original bytes").isEqualTo(original.body());
            assertThat(resend.header(IdempotentResponse.REPLAYED_HEADER)).isEqualTo("true");
            // 새 키로 같은 좌석·다른 회차를 다시 요청해도 새 확보·연장이 아니라 409.
            for (String retry : List.of(body, holdBody(other.id, other.seat(0)))) {
                Reply again = alice.post("/api/v1/holds", retry, key());
                assertThat(again.status()).isEqualTo(409);
                assertThat((String) again.json("$.code")).isEqualTo("ACTIVE_HOLD_EXISTS");
            }
        }
        assertThat(jdbcTemplate.queryForObject("SELECT expires_at FROM holds WHERE id = ?", LocalDateTime.class, holdId))
                .isEqualTo(stored);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM holds", Integer.class)).isEqualTo(1);

        // 만료 시각 이후의 재전송도 원래 응답일 뿐 새 확보를 만들지 않는다. 상태 조회는 만료다.
        time.pin(expiresAt);
        assertThat(alice.post("/api/v1/holds", body, createKey).body()).isEqualTo(original.body());
        assertThat((String) alice.get("/api/v1/holds/" + holdId).json("$.status")).isEqualTo("EXPIRED");
        assertThat(alice.get("/api/v1/holds/current").status()).isEqualTo(204);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM holds", Integer.class)).isEqualTo(1);
        Reply release = alice.post("/api/v1/holds/" + holdId + "/release", null, key());
        assertThat(release.status()).isEqualTo(200);
        assertThat((String) release.json("$.status")).isEqualTo("EXPIRED");
        assertThat(inventory(s.id)).containsOnly("AVAILABLE:null");
    }

    @Test
    void holdNeverOutlivesTheScreeningStartAndNewHoldsStopAtStart() throws Exception {
        signup("start_alice");
        signup("start_bob");
        signup("start_carol");
        Browser alice = login("start_alice");
        Browser bob = login("start_bob");
        Browser carol = login("start_carol");
        Instant p = dbNow();
        Instant startsAt = p.plus(Duration.ofMinutes(2));
        Screening s = screening("시작관", 3, startsAt, 13000);

        time.pin(p);
        Reply early = alice.post("/api/v1/holds", holdBody(s.id, s.seat(0)), key());
        assertThat(early.status()).isEqualTo(201);
        assertThat(Instant.parse((String) early.json("$.expiresAt"))).as("start is earlier than +5m").isEqualTo(startsAt);
        time.pin(startsAt.minus(MICRO));
        Reply lastMoment = bob.post("/api/v1/holds", holdBody(s.id, s.seat(1)), key());
        assertThat(lastMoment.status()).isEqualTo(201);
        assertThat(Instant.parse((String) lastMoment.json("$.expiresAt"))).isEqualTo(startsAt);
        assertThat((String) alice.get("/api/v1/holds/" + early.json("$.holdId")).json("$.status")).isEqualTo("ACTIVE");

        time.pin(startsAt);
        String before = snapshot();
        Reply tooLate = carol.post("/api/v1/holds", holdBody(s.id, s.seat(2)), key());
        assertThat(tooLate.status()).isEqualTo(409);
        assertThat((String) tooLate.json("$.code")).isEqualTo("SCREENING_NOT_BOOKABLE");
        Reply reuse = carol.post("/api/v1/holds", holdBody(s.id, s.seat(0)), key());
        assertThat((String) reuse.json("$.code")).as("expired seats of a started screening stay unbookable")
                .isEqualTo("SCREENING_NOT_BOOKABLE");
        assertThat(snapshot()).isEqualTo(before);
        assertThat((String) alice.get("/api/v1/holds/" + early.json("$.holdId")).json("$.status")).isEqualTo("EXPIRED");
        assertThat((String) bob.get("/api/v1/holds/" + lastMoment.json("$.holdId")).json("$.status")).isEqualTo("EXPIRED");
        Reply release = bob.post("/api/v1/holds/" + lastMoment.json("$.holdId") + "/release", null, key());
        assertThat((String) release.json("$.status")).isEqualTo("EXPIRED");
        assertThat(inventory(s.id)).containsExactly("HELD:" + early.json("$.holdId"), "AVAILABLE:null", "AVAILABLE:null");
    }

    // AC4 작업자 중단·오래된 작업 ----------------------------------------------------------------------------

    @Test
    void whileTheExpiryWorkerIsDownExpiredSeatsCanStillBeReacquired() throws Exception {
        long aliceId = signup("down_alice");
        long bobId = signup("down_bob");
        long carolId = signup("down_carol");
        Browser alice = login("down_alice");
        Browser bob = login("down_bob");
        Browser carol = login("down_carol");
        Screening s = screening("중단관", 4, FAR_START, 6000);
        Screening other = screening("중단 옆관", 1, FAR_START, 6000);
        assertThat(worker.isEnabled()).isFalse();
        long aliceHold = holdId(alice.post("/api/v1/holds", holdBody(s.id, s.seat(0), s.seat(1)), key()));
        long carolHold = holdId(carol.post("/api/v1/holds", holdBody(s.id, s.seat(3)), key()));
        ageHold(aliceHold);
        ageHold(carolHold);
        // 작업자가 반영하지 않아 DB 행은 여전히 ACTIVE·HELD지만, 조회와 확보는 만료로 판정한다.
        assertThat(holdStatus(aliceHold)).isEqualTo("ACTIVE");
        assertThat(inventory(s.id)).containsExactly("HELD:" + aliceHold, "HELD:" + aliceHold, "AVAILABLE:null",
                "HELD:" + carolHold);
        assertThat(JsonPath.<List<String>>read(new Browser().get("/api/v1/screenings/" + s.id + "/seats").body(),
                "$.seats[*].status")).containsOnly("AVAILABLE");

        Reply bobHold = bob.post("/api/v1/holds", holdBody(s.id, s.seat(1), s.seat(2)), key());
        assertThat(bobHold.status()).isEqualTo(201);
        assertThat(holdStatus(aliceHold)).isEqualTo("EXPIRED");
        assertThat(inventory(s.id)).containsExactly("AVAILABLE:null", "HELD:" + holdId(bobHold),
                "HELD:" + holdId(bobHold), "HELD:" + carolHold);
        // 만료된 본인 확보가 남아 있어도 새 확보가 가능하다(같은 트랜잭션에서 기존 것을 만료 처리).
        assertThat(alice.post("/api/v1/holds", holdBody(s.id, s.seat(0)), key()).status()).isEqualTo(201);
        assertThat(carol.post("/api/v1/holds", holdBody(other.id, other.seat(0)), key()).status()).isEqualTo(201);
        assertThat(holdStatus(carolHold)).isEqualTo("EXPIRED");
        assertThat(inventory(s.id).get(3)).isEqualTo("AVAILABLE:null");
        for (long member : List.of(aliceId, bobId, carolId)) {
            assertThat(activeHolds(member)).isEqualTo(1);
        }
    }

    @Test
    void staleExpiryJobsAndLateReleasesNeverTouchTheNewOwner() throws Exception {
        signup("stale_alice");
        signup("stale_bob");
        Browser alice = login("stale_alice");
        Browser bob = login("stale_bob");
        Screening s = screening("오래된 작업관", 3, FAR_START, 6000);
        long aliceHold = holdId(alice.post("/api/v1/holds", holdBody(s.id, s.seat(0), s.seat(1)), key()));
        ageHold(aliceHold);
        // 작업자가 멈추기 전에 읽어 둔 후보 스냅샷.
        List<Long> staleSnapshot = holdService.dueHoldIds(100);
        assertThat(staleSnapshot).containsExactly(aliceHold);

        // 경합: 오래된 작업(alice 확보 만료)과 bob의 재확보가 같은 회차 잠금을 함께 기다린 뒤 진행한다.
        List<Callable<Object>> racers = List.of(
                () -> worker.process(staleSnapshot),
                () -> bob.post("/api/v1/holds", holdBody(s.id, s.seat(1), s.seat(0)), key()));
        List<Object> outcomes = raceBehindLock("SELECT id FROM screenings WHERE id = ? FOR UPDATE", s.id,
                SCREENING_LOCK_WAIT, racers);
        Reply bobReply = (Reply) outcomes.get(1);
        assertThat(bobReply.status()).isEqualTo(201);
        long bobHold = holdId(bobReply);
        System.out.println("[HJ-006] stale job vs reacquire: workerExpired=" + outcomes.get(0)
                + ", reacquire=" + bobReply.status());
        String afterRace = snapshot();
        assertThat(holdStatus(aliceHold)).isEqualTo("EXPIRED");
        assertThat(inventory(s.id)).containsExactly("HELD:" + bobHold, "HELD:" + bobHold, "AVAILABLE:null");

        // 같은 오래된 작업을 다시 실행하고, 아직 유효한 새 확보를 만료 대상으로 넘겨도 아무것도 바뀌지 않는다.
        assertThat(worker.process(staleSnapshot)).isZero();
        assertThat(holdService.expireIfDue(aliceHold)).isFalse();
        assertThat(holdService.expireIfDue(bobHold)).isFalse();
        // 이전 소유자의 늦은 해제도 자기 종결 상태만 돌려주고 새 소유자 좌석을 비우지 않는다.
        Reply lateRelease = alice.post("/api/v1/holds/" + aliceHold + "/release", null, key());
        assertThat(lateRelease.status()).isEqualTo(200);
        assertThat((String) lateRelease.json("$.status")).isEqualTo("EXPIRED");
        String withoutAliceRecord = snapshot();
        assertThat(withoutAliceRecord.substring(0, withoutAliceRecord.lastIndexOf('|')))
                .isEqualTo(afterRace.substring(0, afterRace.lastIndexOf('|')));
        assertThat(holdStatus(bobHold)).isEqualTo("ACTIVE");
    }

    @Test
    void scheduledWorkerExpiresDueHoldsAndLeavesValidOnesAlone() throws Exception {
        signup("worker_alice");
        signup("worker_bob");
        Browser alice = login("worker_alice");
        Browser bob = login("worker_bob");
        Screening s = screening("작업자관", 3, FAR_START, 6000);
        long aliceHold = holdId(alice.post("/api/v1/holds", holdBody(s.id, s.seat(0), s.seat(1)), key()));
        long bobHold = holdId(bob.post("/api/v1/holds", holdBody(s.id, s.seat(2)), key()));
        LocalDateTime lockedAt = salesLockedAt(s.id);
        ageHold(aliceHold);

        worker.setEnabled(true);
        awaitHoldStatus(aliceHold, "EXPIRED", WORKER_TIMEOUT);
        worker.setEnabled(false);

        assertThat(jdbcTemplate.queryForObject("SELECT ended_at FROM holds WHERE id = ?", LocalDateTime.class, aliceHold))
                .isNotNull();
        assertThat(inventory(s.id)).containsExactly("AVAILABLE:null", "AVAILABLE:null", "HELD:" + bobHold);
        assertThat(holdStatus(bobHold)).isEqualTo("ACTIVE");
        assertThat(salesLockedAt(s.id)).as("expiry keeps the sales lock").isEqualTo(lockedAt);
    }

    // AC5 본인 조회·해제·타인 404·반복 해제·재시작·판매 잠금 유지 -------------------------------------------

    @Test
    void ownerReadsAndReleasesOthersGet404RepeatedReleaseIsStableAndSalesLockPersists() throws Exception {
        long aliceId = signup("own_alice");
        long bobId = signup("own_bob");
        Browser alice = login("own_alice");
        Browser bob = login("own_bob");
        Browser operator = operator("own_operator");
        Screening s = screening("본인관", 3, FAR_START, 9000);
        Reply created = alice.post("/api/v1/holds", holdBody(s.id, s.seat(1), s.seat(0)), key());
        long holdId = holdId(created);
        LocalDateTime lockedAt = salesLockedAt(s.id);

        Reply mine = alice.get("/api/v1/holds/" + holdId);
        assertThat(mine.status()).isEqualTo(200);
        assertThat(mine.keys("$")).containsExactlyInAnyOrder("holdId", "screeningId", "status", "seatIds", "expiresAt",
                "totalAmount", "serverNow", "paymentAttemptId", "reservationId");
        assertThat(mine.longs("$.seatIds")).containsExactly(s.seat(0), s.seat(1));
        assertThat(((Number) mine.json("$.totalAmount")).longValue()).isEqualTo(18000);
        assertThat((Object) mine.json("$.paymentAttemptId")).isNull();
        assertThat(((Number) alice.get("/api/v1/holds/current").json("$.holdId")).longValue()).isEqualTo(holdId);

        // 타인·없는 확보: 같은 404, 확보 정보·기록 비노출, DB 무변경. 미인증 401.
        String before = snapshot();
        String bobKey = key();
        List<Reply> foreign = List.of(bob.get("/api/v1/holds/" + holdId), bob.get("/api/v1/holds/" + (holdId + 1000)),
                bob.post("/api/v1/holds/" + holdId + "/release", null, bobKey),
                bob.post("/api/v1/holds/" + holdId + "/release", null, bobKey),
                bob.post("/api/v1/holds/" + (holdId + 1000) + "/release", null, key()));
        for (Reply reply : foreign) {
            assertThat(reply.status()).isEqualTo(404);
            assertThat(reply.keys("$")).containsExactlyInAnyOrder("code", "message");
            assertThat((String) reply.json("$.code")).isEqualTo("HOLD_NOT_FOUND");
            assertThat(reply.body()).doesNotContain(String.valueOf(s.seat(0))).doesNotContain("expiresAt");
        }
        assertThat(foreign.get(0).body()).isEqualTo(foreign.get(1).body());
        assertThat(bob.get("/api/v1/holds/current").status()).isEqualTo(204);
        Browser anonymous = new Browser();
        anonymous.fetchCsrf();
        assertThat(anonymous.get("/api/v1/holds/" + holdId).status()).isEqualTo(401);
        assertThat(anonymous.post("/api/v1/holds/" + holdId + "/release", null, key()).status()).isEqualTo(401);
        assertThat(alice.post("/api/v1/holds/0/release", null, key()).status()).isEqualTo(400);
        assertThat(snapshot()).isEqualTo(before);

        // 본인 해제 → RELEASED, 좌석 반환. 같은 키 재전송은 원래 응답, 새 키 반복 해제도 같은 종결 상태이며 바꾸지 않는다.
        String releaseKey = key();
        Reply released = alice.post("/api/v1/holds/" + holdId + "/release", null, releaseKey);
        assertThat(released.status()).isEqualTo(200);
        assertThat(released.body()).isEqualTo("{\"holdId\":" + holdId + ",\"status\":\"RELEASED\"}");
        assertThat(inventory(s.id)).containsOnly("AVAILABLE:null");
        LocalDateTime endedAt = jdbcTemplate.queryForObject("SELECT ended_at FROM holds WHERE id = ?",
                LocalDateTime.class, holdId);
        Reply replay = alice.post("/api/v1/holds/" + holdId + "/release", null, releaseKey);
        assertThat(replay.body()).isEqualTo(released.body());
        assertThat(replay.header(IdempotentResponse.REPLAYED_HEADER)).isEqualTo("true");
        Reply again = alice.post("/api/v1/holds/" + holdId + "/release", null, key());
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body()).isEqualTo(released.body());
        assertThat(again.header(IdempotentResponse.REPLAYED_HEADER)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT ended_at FROM holds WHERE id = ?", LocalDateTime.class, holdId))
                .isEqualTo(endedAt);
        assertThat(alice.get("/api/v1/holds/current").status()).isEqualTo(204);
        assertThat((String) alice.get("/api/v1/holds/" + holdId).json("$.status")).isEqualTo("RELEASED");

        // 해제된 좌석은 다른 회원이 확보할 수 있다. 확보가 모두 끝나도 최초 판매 잠금은 그대로라 회차 변경·삭제가 막힌다.
        long bobHold = holdId(bob.post("/api/v1/holds", holdBody(s.id, s.seat(0)), key()));
        assertThat(bob.post("/api/v1/holds/" + bobHold + "/release", null, key()).status()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM holds WHERE status = 'ACTIVE'", Integer.class))
                .isZero();
        assertThat(salesLockedAt(s.id)).isEqualTo(lockedAt);
        String screeningBefore = jdbcTemplate.queryForList("SELECT * FROM screenings WHERE id = ?", s.id).toString();
        for (Reply blocked : List.of(
                operator.send("PATCH", "/api/v1/ops/screenings/" + s.id, "{\"version\":0,\"basePrice\":1}", List.of()),
                operator.send("PATCH", "/api/v1/ops/screenings/" + s.id,
                        "{\"version\":0,\"startsAt\":\"2031-08-01T01:00:00Z\"}", List.of()),
                operator.send("DELETE", "/api/v1/ops/screenings/" + s.id, null, List.of()))) {
            assertThat(blocked.status()).isEqualTo(409);
            assertThat((String) blocked.json("$.code")).isEqualTo("SALES_LOCKED");
        }
        assertThat(jdbcTemplate.queryForList("SELECT * FROM screenings WHERE id = ?", s.id).toString())
                .isEqualTo(screeningBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE member_id = ? "
                + "AND operation = 'HOLD_RELEASE'", Integer.class, bobId)).as("bob's 404s left no records").isEqualTo(1);
        assertThat(aliceId).isPositive();
    }

    @Test
    void holdsAndIdempotentResultsSurviveAnApplicationRestartAndTheRestartedWorkerResumes() throws Exception {
        signup("restart_alice");
        signup("restart_bob");
        Browser alice = login("restart_alice");
        Browser bob = login("restart_bob");
        Screening s = screening("재시작관", 3, FAR_START, 7000);
        String createKey = key();
        String body = holdBody(s.id, s.seat(0));
        Reply original = alice.post("/api/v1/holds", body, createKey);
        long aliceHold = holdId(original);
        long bobHold = holdId(bob.post("/api/v1/holds", holdBody(s.id, s.seat(1), s.seat(2)), key()));
        ageHold(bobHold);
        LocalDateTime lockedAt = salesLockedAt(s.id);

        try (ConfigurableApplicationContext restarted = startSecondInstance()) {
            int restartedPort = restarted.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            assertThat(restartedPort).isNotEqualTo(port);
            Browser aliceThere = alice.on(restartedPort);
            // 세션(JDBC)·확보·멱등 기록은 DB에 있으므로 새 인스턴스에서 그대로 이어진다.
            Reply current = aliceThere.get("/api/v1/holds/current");
            assertThat(current.status()).isEqualTo(200);
            assertThat(((Number) current.json("$.holdId")).longValue()).isEqualTo(aliceHold);
            assertThat((String) current.json("$.expiresAt")).isEqualTo(original.json("$.expiresAt"));
            Reply replay = aliceThere.post("/api/v1/holds", body, createKey);
            assertThat(replay.status()).isEqualTo(201);
            assertThat(replay.body()).isEqualTo(original.body());
            assertThat(replay.header(IdempotentResponse.REPLAYED_HEADER)).isEqualTo("true");
            assertThat((Boolean) aliceThere.get("/api/v1/screenings/" + s.id + "/seats").json("$.seats[0].mine")).isTrue();

            // 재시작한 인스턴스의 작업자가 중단 동안 만료된 확보를 이어서 정리한다.
            awaitHoldStatus(bobHold, "EXPIRED", WORKER_TIMEOUT);
            assertThat(holdStatus(aliceHold)).isEqualTo("ACTIVE");
            Reply released = aliceThere.post("/api/v1/holds/" + aliceHold + "/release", null, key());
            assertThat((String) released.json("$.status")).isEqualTo("RELEASED");
        }
        assertThat((String) alice.get("/api/v1/holds/" + aliceHold).json("$.status")).isEqualTo("RELEASED");
        assertThat(inventory(s.id)).containsOnly("AVAILABLE:null");
        assertThat(salesLockedAt(s.id)).isEqualTo(lockedAt);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM holds", Integer.class)).isEqualTo(2);
    }

    // 경합 도구 ---------------------------------------------------------------------------------------------

    /**
     * 별도 연결이 {@code lockSql} 행 잠금을 쥔 채 경쟁 작업을 barrier로 함께 시작한다. 해제 작업은 모든 경쟁 작업이 실제로
     * {@code waitingLike} 잠금 문장에서 대기 중임을 processlist로 확인한 뒤 잠금을 푼다. 결과는 경쟁 작업 순서대로 돌려준다.
     */
    private List<Object> raceBehindLock(String lockSql, long lockedId, String waitingLike,
            List<Callable<Object>> racers) throws Exception {
        try (Connection blocker = TestDatabaseGuard.openConnection(settings)) {
            TestDatabaseGuard.requireTestSchema(blocker.getCatalog());
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement(lockSql)) {
                lock.setLong(1, lockedId);
                lock.executeQuery().close();
            }
            AtomicInteger observed = new AtomicInteger(-1);
            List<ConcurrentRunner.BarrierTask<Object>> tasks = new ArrayList<>();
            for (Callable<Object> racer : racers) {
                tasks.add(gate -> {
                    gate.await();
                    return racer.call();
                });
            }
            tasks.add(gate -> {
                gate.await();
                try {
                    observed.set(awaitLockWaiters(waitingLike, racers.size()));
                } finally {
                    blocker.rollback();
                }
                return "released";
            });
            List<Object> results = ConcurrentRunner.runWithGate(CONCURRENCY_TIMEOUT, tasks);
            assertThat(observed).as("all racers were waiting on the same lock").hasValue(racers.size());
            return results.subList(0, racers.size());
        }
    }

    private int awaitLockWaiters(String waitingLike, int expected) {
        long deadline = System.nanoTime() + WAITER_GATE_TIMEOUT.toNanos();
        int waiting = 0;
        while (System.nanoTime() < deadline) {
            waiting = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.processlist "
                    + "WHERE id <> CONNECTION_ID() AND info LIKE ?", Integer.class, waitingLike);
            if (waiting >= expected) {
                return waiting;
            }
            // 조건 확인용 짧은 폴링 간격이다. 정합성 판정에 쓰는 sleep이 아니다.
            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        }
        throw new IllegalStateException("expected " + expected + " lock waiters, saw " + waiting);
    }

    private void awaitHoldStatus(long holdId, String status, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!status.equals(holdStatus(holdId))) {
            assertThat(System.nanoTime()).as("hold %d did not become %s within %s", holdId, status, timeout)
                    .isLessThan(deadline);
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
    }

    /**
     * 같은 DB·세션 저장소를 쓰는 두 번째 애플리케이션 인스턴스(재시작 모사). 만료 작업자를 켜고 짧은 주기로 실행한다.
     * 테스트 클래스(test-classes)의 구성·컨트롤러는 스캔에서 제외해 운영 구성만 올린다.
     */
    private static ConfigurableApplicationContext startSecondInstance() {
        ApplicationContextInitializer<ConfigurableApplicationContext> excludeTests =
                context -> context.getBeanFactory().registerSingleton("hjExcludeTestClasses", new ExcludeTestClasses());
        return new SpringApplicationBuilder(HanjariApplication.class)
                .initializers(excludeTests)
                .run("--spring.datasource.url=" + settings.url(),
                        "--spring.datasource.username=" + settings.username(),
                        "--spring.datasource.password=" + settings.password(),
                        "--spring.flyway.locations=classpath:db/migration",
                        "--server.port=0",
                        "--hanjari.security.allowed-origins=" + ORIGIN,
                        "--hanjari.holds.expiry-worker.enabled=true",
                        "--hanjari.holds.expiry-worker.interval-ms=200");
    }

    /** 테스트 출력 디렉터리의 클래스(테스트 전용 구성·probe 컨트롤러)를 컴포넌트 스캔에서 제외한다. */
    static final class ExcludeTestClasses extends TypeExcludeFilter {
        @Override
        public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory)
                throws IOException {
            return metadataReader.getResource().getURL().toString().contains("/test-classes/");
        }

        @Override
        public boolean equals(Object other) {
            return other != null && other.getClass() == getClass();
        }

        @Override
        public int hashCode() {
            return getClass().hashCode();
        }
    }

    // fixture·조회 ------------------------------------------------------------------------------------------

    private record Screening(long id, List<Long> seatIds) {
        long seat(int index) {
            return seatIds.get(index);
        }
    }

    /** 영화·상영관·좌석(A행 1..n)·회차·inventory를 직접 만든다(과거·근접 시작 시각을 위해 운영 API를 거치지 않는다). */
    private Screening screening(String theaterName, int seats, Instant startsAt, long price) {
        long movie = insert("INSERT INTO movies (title, runtime_minutes, age_rating) VALUES (?, ?, ?)",
                theaterName + " 영화", 100, "ALL");
        long theater = insert("INSERT INTO theaters (name) VALUES (?)", theaterName);
        List<Long> seatIds = new ArrayList<>();
        for (int number = 1; number <= seats; number++) {
            seatIds.add(insert("INSERT INTO seats (theater_id, row_label, seat_number) VALUES (?, 'A', ?)",
                    theater, number));
        }
        long id = insert("INSERT INTO screenings (movie_id, theater_id, starts_at, ends_at, base_price) "
                        + "VALUES (?, ?, ?, ?, ?)", movie, theater, ScreeningSchedule.toDb(startsAt),
                ScreeningSchedule.toDb(ScreeningSchedule.endsAt(startsAt, 100)), price);
        jdbcTemplate.update("INSERT INTO screening_seats (screening_id, theater_id, seat_id, state) "
                + "SELECT ?, theater_id, id, 'AVAILABLE' FROM seats WHERE theater_id = ?", id, theater);
        return new Screening(id, seatIds);
    }

    /** API를 거치지 않는 ACTIVE 확보 행(좌석 없음). DB 제약 검증용. */
    private long fixtureHold(long memberId, long screeningId) {
        return insert("INSERT INTO holds (member_id, screening_id, status, total_amount, expires_at, created_at) "
                + "VALUES (?, ?, 'ACTIVE', 0, '2031-07-01 01:00:00', '2031-07-01 00:55:00')", memberId, screeningId);
    }

    private long fixtureOwnerOf(long screeningId, long seatId) {
        return jdbcTemplate.queryForObject("SELECT hold_id FROM screening_seats WHERE screening_id = ? AND seat_id = ?",
                Long.class, screeningId, seatId);
    }

    /** 작업자 중단 동안 만료 시각이 지난 확보를 재현한다(생성 6분 전, 만료 1분 전). 상태·좌석은 그대로 둔다. */
    private void ageHold(long holdId) {
        assertThat(jdbcTemplate.update("UPDATE holds SET created_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 6 MINUTE), "
                + "expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE) WHERE id = ? AND status = 'ACTIVE'",
                holdId)).isEqualTo(1);
    }

    private LocalDateTime holdTime(long holdId, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM holds WHERE id = ?", LocalDateTime.class, holdId);
    }

    private String holdStatus(long holdId) {
        return jdbcTemplate.queryForObject("SELECT status FROM holds WHERE id = ?", String.class, holdId);
    }

    private int activeHolds(long memberId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM holds WHERE member_id = ? AND status = 'ACTIVE'",
                Integer.class, memberId);
    }

    /** 좌석 ID 순서의 "상태:소유 확보" 목록. */
    private List<String> inventory(long screeningId) {
        return jdbcTemplate.queryForList("SELECT state, hold_id FROM screening_seats WHERE screening_id = ? "
                + "ORDER BY seat_id", screeningId).stream().map(r -> r.get("state") + ":" + r.get("hold_id")).toList();
    }

    private LocalDateTime salesLockedAt(long screeningId) {
        return jdbcTemplate.queryForObject("SELECT sales_locked_at FROM screenings WHERE id = ?", LocalDateTime.class,
                screeningId);
    }

    /** 확보·확보 좌석·inventory·회차(판매 잠금·version)·멱등 기록 전체의 사후 상태. 멱등 기록이 마지막 구간이다. */
    private String snapshot() {
        return jdbcTemplate.queryForList("SELECT * FROM holds ORDER BY id")
                + "|" + jdbcTemplate.queryForList("SELECT * FROM hold_items ORDER BY hold_id, seat_id")
                + "|" + jdbcTemplate.queryForList("SELECT screening_id, seat_id, state, hold_id FROM screening_seats "
                + "ORDER BY screening_id, seat_id")
                + "|" + jdbcTemplate.queryForList("SELECT id, starts_at, base_price, sales_locked_at, version "
                + "FROM screenings ORDER BY id")
                + "|" + jdbcTemplate.queryForList("SELECT * FROM idempotency_records ORDER BY id");
    }

    private Instant dbNow() {
        return ScreeningSchedule.fromDb(jdbcTemplate.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class))
                .truncatedTo(ChronoUnit.MICROS);
    }

    private static long holdId(Reply reply) {
        assertThat(reply.status()).as(reply.body()).isEqualTo(201);
        return ((Number) reply.json("$.holdId")).longValue();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String holdBody(long screeningId, long... seatIds) {
        StringBuilder seats = new StringBuilder();
        for (long seatId : seatIds) {
            if (seats.length() > 0) {
                seats.append(',');
            }
            seats.append(seatId);
        }
        return "{\"screeningId\":" + screeningId + ",\"seatIds\":[" + seats + "]}";
    }

    private void clean() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            TestDataCleaner.cleanAll(connection);
        }
    }

    private long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private long signup(String loginId) throws Exception {
        Browser browser = new Browser();
        browser.fetchCsrf();
        Reply reply = browser.post("/api/v1/auth/signup",
                "{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}", null);
        assertThat(reply.status()).as("signup %s", loginId).isEqualTo(201);
        return ((Number) reply.json("$.memberId")).longValue();
    }

    private Browser login(String loginId) throws Exception {
        Browser browser = new Browser();
        browser.fetchCsrf();
        assertThat(browser.post("/api/v1/auth/login",
                "{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}", null).status()).isEqualTo(200);
        browser.fetchCsrf();
        return browser;
    }

    /** 격리 테스트 DB에서만 역할을 바꾼 운영자 fixture. */
    private Browser operator(String loginId) throws Exception {
        long memberId = signup(loginId);
        jdbcTemplate.update("UPDATE members SET role = 'OPERATOR' WHERE id = ?", memberId);
        return login(loginId);
    }

    private record Reply(int status, String body, Map<String, List<String>> headers) {
        Object json(String path) {
            return JsonPath.read(body, path);
        }

        List<String> keys(String path) {
            Map<String, Object> object = JsonPath.read(body, path);
            return List.copyOf(object.keySet());
        }

        List<Long> longs(String path) {
            List<Number> values = JsonPath.read(body, path);
            return values.stream().map(Number::longValue).toList();
        }

        String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }
    }

    /** 세션 쿠키 하나와 CSRF 토큰을 직접 관리하는 최소 HTTP 클라이언트. 동시 요청에는 {@link #copy()}를 쓴다. */
    private final class Browser {
        int targetPort = port;
        String sessionCookie;
        String csrfToken;
        String csrfHeader = "X-CSRF-TOKEN";

        Browser copy() {
            return on(targetPort);
        }

        /** 같은 세션으로 다른 인스턴스(포트)에 보내는 사본. */
        Browser on(int otherPort) {
            Browser copy = new Browser();
            copy.targetPort = otherPort;
            copy.sessionCookie = sessionCookie;
            copy.csrfToken = csrfToken;
            copy.csrfHeader = csrfHeader;
            return copy;
        }

        void fetchCsrf() throws IOException, InterruptedException {
            Reply reply = get("/api/v1/auth/csrf");
            assertThat(reply.status()).isEqualTo(200);
            csrfHeader = (String) reply.json("$.headerName");
            csrfToken = (String) reply.json("$.token");
        }

        Reply get(String path) throws IOException, InterruptedException {
            return send("GET", path, null, List.of(), false);
        }

        /** key가 null이면 Idempotency-Key 헤더 없이 보낸다. */
        Reply post(String path, String json, String key) throws IOException, InterruptedException {
            return send("POST", path, json, key == null ? List.of() : List.of(key));
        }

        Reply send(String method, String path, String json, List<String> keys) throws IOException, InterruptedException {
            return send(method, path, json, keys, true);
        }

        private Reply send(String method, String path, String json, List<String> keys, boolean stateChanging)
                throws IOException, InterruptedException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + targetPort + path))
                    .timeout(HTTP_TIMEOUT)
                    .method(method, json == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            if (json != null) {
                builder.header("Content-Type", "application/json");
            }
            if (stateChanging) {
                builder.header("Origin", ORIGIN);
                if (csrfToken != null) {
                    builder.header(csrfHeader, csrfToken);
                }
            }
            for (String key : keys) {
                builder.header(IdempotencyKey.HEADER, key);
            }
            if (sessionCookie != null) {
                builder.header("Cookie", COOKIE + "=" + sessionCookie);
            }
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            for (String setCookie : response.headers().allValues("Set-Cookie")) {
                if (setCookie.startsWith(COOKIE + "=")) {
                    String value = setCookie.substring(COOKIE.length() + 1).split(";", 2)[0];
                    sessionCookie = value.isEmpty() || setCookie.contains("Max-Age=0") ? null : value;
                }
            }
            return new Reply(response.statusCode(), response.body(), response.headers().map());
        }
    }
}
