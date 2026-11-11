package com.hanjari.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hanjari.auth.AuthenticatedMember;
import com.hanjari.auth.SessionCookieConfig;
import com.hanjari.common.ApiException;
import com.hanjari.common.RequestValues;
import com.hanjari.screening.ScreeningSchedule;
import com.hanjari.testsupport.ConcurrentRunner;
import com.hanjari.testsupport.TestDataCleaner;
import com.hanjari.testsupport.TestDatabaseGuard;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HJ-005: 실제 MySQL(hanjari_test)과 실제 HTTP 서버(임의 포트)로 멱등 기록의 영속·동시성·롤백·범위·비유출을 검증한다.
 *
 * <p>확보·결제·취소 기능은 후속 업무라 아직 없다. 그래서 이 테스트 전용 probe 컨트롤러({@link ProbeController},
 * 이 클래스 안에서만 등록됨)가 실제 실행기({@link IdempotencyExecutor})·실제 지문 계약({@link IdempotentRequest})·실제 보안 체인
 * (Origin → CSRF → 인증)을 그대로 쓰면서, 실제 도메인 테이블(screening_seats 상태, screenings.sales_locked_at)을 바꾼다.
 * probe 자원의 소유자 매핑은 테스트 fixture(메모리)이며 변경 대상이 아니다. 판정은 응답과 DB 사후 상태로 한다.
 * 동시성은 시작 barrier·유한 timeout({@link ConcurrentRunner})을 쓰고, 경쟁 요청이 실제로 같은 키 선점에서 대기 중인지를
 * processlist로 확인한 뒤 승자를 진행시킨다(임의 sleep으로 순서를 가정하지 않는다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "hanjari.security.allowed-origins=http://localhost:5173")
class HjIdempotencyPersistenceIT {

    private static final String ORIGIN = "http://localhost:5173";
    private static final String COOKIE = SessionCookieConfig.COOKIE_NAME;
    private static final String PASSWORD = "idempotency password 1";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(100);
    private static final Duration CONCURRENCY_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration WAITER_GATE_TIMEOUT = Duration.ofSeconds(30);
    private static final String BASE = "/api/v1/test-idempotency";

    private static TestDatabaseGuard.Settings settings;

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        settings = TestDatabaseGuard.verifiedFromEnvironment();
        TestDatabaseGuard.register(registry, settings);
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        ProbeHooks probeHooks() {
            return new ProbeHooks();
        }

        @Bean
        ProbeController idempotencyProbeController(IdempotencyExecutor executor, JdbcTemplate jdbcTemplate,
                ProbeHooks hooks) {
            return new ProbeController(executor, jdbcTemplate, hooks);
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ProbeHooks hooks;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void cleanBefore() throws SQLException {
        clean();
        hooks.reset();
    }

    /** 다른 IT의 일괄 삭제가 FK에 막히지 않도록 끝난 뒤에도 비운다. */
    @AfterEach
    void cleanAfter() throws SQLException {
        hooks.reset();
        clean();
    }

    // AC1 같은 키·같은 내용(좌석 순서 무관) → 원래 응답, 중복 좌석은 거부 ---------------------------------------

    @Test
    void sameKeyAndSameContentIncludingSeatPermutationReplaysTheOriginalResponse() throws Exception {
        long memberId = signup("idem_replay");
        Browser member = login("idem_replay");
        Screening s = screeningWithSeats("재전송관", 4, memberId);
        String key = UUID.randomUUID().toString();

        Reply first = member.post(BASE + "/holds", holdBody(s.id, s.seat(2), s.seat(0), s.seat(1)), key);
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.header(IdempotentResponse.REPLAYED_HEADER)).isNull();
        assertThat(first.longs("$.seatIds")).containsExactly(s.seat(0), s.seat(1), s.seat(2));
        LocalDateTime lockedAt = salesLockedAt(s.id);
        assertThat(lockedAt).isNotNull();
        String seatsAfterFirst = seatStates(s.id);

        // 같은 본문, 좌석 순서만 다른 본문, 대문자 표기 키 → 모두 같은 원래 응답(상태·본문 바이트 동일), 도메인 재실행 없음.
        List<Reply> replays = List.of(
                member.post(BASE + "/holds", holdBody(s.id, s.seat(2), s.seat(0), s.seat(1)), key),
                member.post(BASE + "/holds", holdBody(s.id, s.seat(0), s.seat(1), s.seat(2)), key),
                member.post(BASE + "/holds", "{\"seatIds\":[" + s.seat(1) + "," + s.seat(2) + "," + s.seat(0)
                        + "],\"screeningId\":" + s.id + "}", key.toUpperCase()));
        for (Reply replay : replays) {
            assertThat(replay.status()).isEqualTo(201);
            assertThat(replay.body()).isEqualTo(first.body());
            assertThat(replay.header(IdempotentResponse.REPLAYED_HEADER)).isEqualTo("true");
        }
        assertThat(hooks.domainRuns).hasValue(1);
        assertThat(salesLockedAt(s.id)).as("sales lock written once").isEqualTo(lockedAt);
        assertThat(seatStates(s.id)).isEqualTo(seatsAfterFirst);
        assertThat(records(memberId)).singleElement().satisfies(row -> {
            assertThat(row.get("operation")).isEqualTo("HOLD_CREATE");
            assertThat(row.get("idempotency_key")).isEqualTo(key);
            assertThat(((Number) row.get("http_status")).intValue()).isEqualTo(201);
            assertThat(row.get("response_body")).isEqualTo(first.body());
            assertThat(row.get("request_hash")).isEqualTo(
                    IdempotentRequest.holdCreate(s.id, List.of(s.seat(0), s.seat(1), s.seat(2))).hash());
        });

        // 중복 좌석 ID는 정규화로 숨기지 않는다: 기존 키로 보내도 재전송이 아니라 400, 새 키여도 400. 기록·도메인 변화 없음.
        String before = snapshot();
        for (String k : List.of(key, UUID.randomUUID().toString())) {
            Reply duplicate = member.post(BASE + "/holds", holdBody(s.id, s.seat(0), s.seat(1), s.seat(0)), k);
            assertThat(duplicate.status()).isEqualTo(400);
            assertThat(duplicate.keys()).containsExactlyInAnyOrder("code", "message");
            assertThat((String) duplicate.json("$.code")).isEqualTo("DUPLICATE_SEAT_ID");
            assertThat(duplicate.header(IdempotentResponse.REPLAYED_HEADER)).isNull();
        }
        Reply freshDuplicate = member.post(BASE + "/holds", holdBody(s.id, s.seat(3), s.seat(3)),
                UUID.randomUUID().toString());
        assertThat((String) freshDuplicate.json("$.code")).isEqualTo("DUPLICATE_SEAT_ID");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(hooks.domainRuns).hasValue(1);
    }

    // AC2 키 형식·같은 키의 다른 경로·본문 → 409 ------------------------------------------------------------

    @Test
    void keyFormatIsValidatedAndSameKeyWithDifferentPathOrBodyConflictsWithoutSideEffects() throws Exception {
        long memberId = signup("idem_conflict");
        Browser member = login("idem_conflict");
        Screening s1 = screeningWithSeats("충돌 1관", 3, memberId);
        Screening s2 = screeningWithSeats("충돌 2관", 3, memberId);

        // 키 없음·형식 위반·중복 헤더 → 400, 기록·도메인 변화 없음.
        String before = snapshot();
        Reply missing = member.send("POST", BASE + "/holds", holdBody(s1.id, s1.seat(0)), List.of());
        assertThat(missing.status()).isEqualTo(400);
        assertThat((String) missing.json("$.code")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        String valid = UUID.randomUUID().toString();
        for (List<String> header : List.of(List.of("abc"), List.of("{" + valid + "}"), List.of(valid.replace("-", "")),
                List.of(valid, valid), List.of(""))) {
            Reply invalid = member.send("POST", BASE + "/holds", holdBody(s1.id, s1.seat(0)), header);
            assertThat(invalid.status()).as("%s", header).isEqualTo(400);
            assertThat((String) invalid.json("$.code")).isEqualTo("INVALID_IDEMPOTENCY_KEY");
            assertThat(invalid.body()).doesNotContain(valid).doesNotContain("abc");
        }
        assertThat(snapshot()).isEqualTo(before);
        assertThat(hooks.domainRuns).hasValue(0);

        // 다른 본문(좌석 집합·회차) → 409 IDEMPOTENCY_CONFLICT. 원래 응답을 담지 않고, 도메인은 그대로.
        String createKey = UUID.randomUUID().toString();
        Reply created = member.post(BASE + "/holds", holdBody(s1.id, s1.seat(0), s1.seat(1)), createKey);
        assertThat(created.status()).isEqualTo(201);
        String originalToken = (String) created.json("$.token");
        String afterCreate = snapshot();
        for (String body : List.of(holdBody(s1.id, s1.seat(0)), holdBody(s1.id, s1.seat(0), s1.seat(1), s1.seat(2)),
                holdBody(s2.id, s2.seat(0), s2.seat(1)))) {
            Reply conflict = member.post(BASE + "/holds", body, createKey);
            assertThat(conflict.status()).as(body).isEqualTo(409);
            assertThat(conflict.keys()).containsExactlyInAnyOrder("code", "message");
            assertThat((String) conflict.json("$.code")).isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(conflict.body()).doesNotContain(originalToken);
        }
        assertThat(snapshot()).isEqualTo(afterCreate);

        // 같은 operation, 같은 키, 다른 경로(대상 ID) → 409. 두 번째 대상은 바뀌지 않는다.
        markHeld(s2.id, null, memberId);
        String releaseKey = UUID.randomUUID().toString();
        Reply released = member.post(BASE + "/holds/" + s1.id + "/release", null, releaseKey);
        assertThat(released.status()).isEqualTo(200);
        String afterRelease = snapshot();
        Reply otherPath = member.post(BASE + "/holds/" + s2.id + "/release", null, releaseKey);
        assertThat(otherPath.status()).isEqualTo(409);
        assertThat((String) otherPath.json("$.code")).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(snapshot()).isEqualTo(afterRelease);
        assertThat(seatStates(s2.id)).doesNotContain("AVAILABLE");
        // 원래 요청은 계속 원래 응답으로 재전송된다.
        assertThat(member.post(BASE + "/holds/" + s1.id + "/release", null, releaseKey).body())
                .isEqualTo(released.body());
        assertThat(member.post(BASE + "/holds", holdBody(s1.id, s1.seat(1), s1.seat(0)), createKey).body())
                .isEqualTo(created.body());
        assertThat(records(memberId)).hasSize(2);
        assertThat(hooks.domainRuns).hasValue(2);
    }

    // AC3 동시 동일 키 → 한 번만 반영 -------------------------------------------------------------------------

    @Test
    void concurrentRequestsWithTheSameKeyApplyExactlyOnceAndAllReceiveTheOriginalResponse() throws Exception {
        long memberId = signup("idem_race");
        Browser member = login("idem_race");
        Screening s = screeningWithSeats("동시 키관", 3, memberId);
        String key = UUID.randomUUID().toString();
        int requests = 8;
        // 승자는 도메인 변경 후 커밋 전에, 나머지 7개가 실제로 같은 키 선점(INSERT)에서 DB 대기 중인지 확인한 뒤 진행한다.
        AtomicInteger observedWaiters = new AtomicInteger(-1);
        hooks.afterDomainWrite.set(jdbc -> observedWaiters.set(awaitClaimWaiters(jdbc, requests - 1)));

        List<ConcurrentRunner.BarrierTask<Reply>> tasks = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            String body = i % 2 == 0 ? holdBody(s.id, s.seat(0), s.seat(1), s.seat(2))
                    : holdBody(s.id, s.seat(2), s.seat(1), s.seat(0));
            tasks.add(gate -> {
                Browser mine = member.copy();
                gate.await();
                return mine.post(BASE + "/holds", body, key);
            });
        }
        List<Reply> replies = ConcurrentRunner.runWithGate(CONCURRENCY_TIMEOUT, tasks);

        assertThat(observedWaiters).hasValue(requests - 1);
        // 판정 기준은 DB: 기록 1행, 좌석은 한 번만 HELD, 도메인 작업 1회.
        assertThat(records(memberId)).hasSize(1);
        assertThat(seatStates(s.id)).isEqualTo("[HELD, HELD, HELD]");
        assertThat(hooks.domainRuns).hasValue(1);
        assertThat(replies).extracting(Reply::status).containsOnly(201);
        assertThat(replies).extracting(Reply::body).containsOnly(replies.get(0).body());
        assertThat(replies).filteredOn(r -> r.header(IdempotentResponse.REPLAYED_HEADER) == null).hasSize(1);
        assertThat((String) records(memberId).get(0).get("response_body")).isEqualTo(replies.get(0).body());
        System.out.println("[HJ-005] concurrent same key: statuses=" + replies.stream().map(Reply::status).toList()
                + ", waitersObserved=" + observedWaiters.get() + ", domainRuns=" + hooks.domainRuns.get());
    }

    @Test
    void whenTheFirstClaimRollsBackAConcurrentSameKeyRequestExecutesOnceAndRetriesConverge() throws Exception {
        long memberId = signup("idem_race_rb");
        Browser member = login("idem_race_rb");
        Screening s = screeningWithSeats("롤백 경합관", 2, memberId);
        String key = UUID.randomUUID().toString();
        String body = holdBody(s.id, s.seat(1), s.seat(0));
        int requests = 6;
        AtomicInteger invocation = new AtomicInteger();
        AtomicInteger observedWaiters = new AtomicInteger(-1);
        // 첫 실행: 경쟁 요청이 모두 대기 중임을 확인한 뒤 도메인 변경 후 실패 → 도메인·기록 함께 롤백. 이후 실행은 정상.
        hooks.afterDomainWrite.set(jdbc -> {
            if (invocation.incrementAndGet() == 1) {
                observedWaiters.set(awaitClaimWaiters(jdbc, requests - 1));
                throw new IllegalStateException("injected failure after domain write");
            }
        });

        List<ConcurrentRunner.BarrierTask<Reply>> tasks = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            tasks.add(gate -> {
                Browser mine = member.copy();
                gate.await();
                return mine.post(BASE + "/holds", body, key);
            });
        }
        List<Reply> replies = new ArrayList<>(ConcurrentRunner.runWithGate(CONCURRENCY_TIMEOUT, tasks));
        assertThat(observedWaiters).hasValue(requests - 1);
        List<Integer> statuses = replies.stream().map(Reply::status).toList();
        System.out.println("[HJ-005] same key after winner rollback: statuses=" + statuses
                + ", domainRuns=" + hooks.domainRuns.get());
        // 실패한 첫 요청은 500 하나. 나머지는 새 실행·재전송(201)이거나, 대기자끼리의 데드락 등 일시 장애(503, 같은 키 재시도 안내)다.
        assertThat(statuses).filteredOn(st -> st == 500).hasSize(1);
        assertThat(statuses).allMatch(st -> st == 500 || st == 201 || st == 503);

        // 201이 아니었던 요청을 같은 키로 다시 보내면 모두 같은 원래 응답으로 수렴한다.
        List<Reply> all = new ArrayList<>();
        for (Reply reply : replies) {
            all.add(reply.status() == 201 ? reply : member.post(BASE + "/holds", body, key));
        }
        assertThat(all).extracting(Reply::status).containsOnly(201);
        assertThat(all).extracting(Reply::body).containsOnly(all.get(0).body());
        assertThat(all).filteredOn(r -> r.header(IdempotentResponse.REPLAYED_HEADER) == null).hasSize(1);
        // DB: 롤백된 첫 실행은 흔적이 없고, 반영은 정확히 한 번.
        assertThat(hooks.domainRuns).hasValue(2);
        assertThat(records(memberId)).singleElement()
                .satisfies(row -> assertThat(row.get("response_body")).isEqualTo(all.get(0).body()));
        assertThat(seatStates(s.id)).isEqualTo("[HELD, HELD]");
    }

    // AC4 범위 분리, 도메인·기록 동시 롤백 후 재시도 ------------------------------------------------------------

    @Test
    void recordsAreScopedPerMemberAndPerOperation() throws Exception {
        long aliceId = signup("idem_alice");
        long bobId = signup("idem_bob");
        Browser alice = login("idem_alice");
        Browser bob = login("idem_bob");
        Screening forAlice = screeningWithSeats("범위 A관", 2, aliceId);
        Screening forBob = screeningWithSeats("범위 B관", 2, bobId);
        String shared = UUID.randomUUID().toString();

        Reply aliceHold = alice.post(BASE + "/holds", holdBody(forAlice.id, forAlice.seat(0)), shared);
        // 다른 회원의 같은 키는 별도 범위: 충돌·재전송 없이 자기 요청을 실행한다.
        Reply bobHold = bob.post(BASE + "/holds", holdBody(forBob.id, forBob.seat(0)), shared);
        assertThat(aliceHold.status()).isEqualTo(201);
        assertThat(bobHold.status()).isEqualTo(201);
        assertThat(bobHold.header(IdempotentResponse.REPLAYED_HEADER)).isNull();
        assertThat(bobHold.body()).isNotEqualTo(aliceHold.body());
        // 같은 회원의 같은 키라도 operation이 다르면 별도 범위다(같은 키로 확보 해제·결제 접수).
        Reply aliceRelease = alice.post(BASE + "/holds/" + forAlice.id + "/release", null, shared);
        assertThat(aliceRelease.status()).isEqualTo(200);
        assertThat(aliceRelease.header(IdempotentResponse.REPLAYED_HEADER)).isNull();
        markHeld(forAlice.id, forAlice.seat(1), aliceId);
        Reply aliceAccept = alice.post(BASE + "/holds/" + forAlice.id + "/payment-attempts", null, shared);
        assertThat(aliceAccept.status()).isEqualTo(202);

        assertThat(jdbcTemplate.queryForList("SELECT member_id, operation FROM idempotency_records "
                        + "WHERE idempotency_key = ? ORDER BY member_id, operation", shared))
                .extracting(row -> ((Number) row.get("member_id")).longValue() + ":" + row.get("operation"))
                .containsExactlyInAnyOrder(aliceId + ":HOLD_CREATE", aliceId + ":HOLD_RELEASE",
                        aliceId + ":PAYMENT_ATTEMPT_CREATE", bobId + ":HOLD_CREATE");
        // 각 범위는 자기 원래 응답만 재전송한다.
        assertThat(alice.post(BASE + "/holds", holdBody(forAlice.id, forAlice.seat(0)), shared).body())
                .isEqualTo(aliceHold.body());
        assertThat(bob.post(BASE + "/holds", holdBody(forBob.id, forBob.seat(0)), shared).body())
                .isEqualTo(bobHold.body());
        // 회원 범위의 고유 제약은 DB가 강제한다.
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO idempotency_records "
                + "(member_id, operation, idempotency_key, request_hash, created_at) "
                + "VALUES (?, 'HOLD_CREATE', ?, REPEAT('a', 64), UTC_TIMESTAMP(6))", aliceId, shared))
                .isInstanceOf(DataAccessException.class);
        assertThat(hooks.domainRuns).hasValue(4);
    }

    @Test
    void domainChangeAndRecordRollBackTogetherAndTheSameKeyCanBeRetried() throws Exception {
        long memberId = signup("idem_rollback");
        Browser member = login("idem_rollback");
        Screening s = screeningWithSeats("롤백관", 3, memberId);
        String key = UUID.randomUUID().toString();
        String body = holdBody(s.id, s.seat(0), s.seat(1));
        String pristine = snapshot();
        String sensitive = "jdbc:mysql://secret-host/hanjari_test password=" + PASSWORD;

        // (1) 도메인 변경 후 예기치 않은 실패 → 500, 좌석·판매 잠금·기록 모두 롤백.
        // 트랜잭션 안에서 본 값은 기록만 하고 판정은 테스트 스레드에서 한다(처리기 안의 AssertionError도 500이 되어 구분되지 않으므로).
        AtomicInteger heldInsideTx = new AtomicInteger(-1);
        AtomicInteger claimsInsideTx = new AtomicInteger(-1);
        AtomicReference<Object> lockInsideTx = new AtomicReference<>();
        hooks.afterDomainWrite.set(jdbc -> {
            heldInsideTx.set(jdbc.queryForObject("SELECT COUNT(*) FROM screening_seats WHERE screening_id = ? "
                    + "AND state = 'HELD'", Integer.class, s.id));
            claimsInsideTx.set(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records "
                    + "WHERE http_status IS NULL", Integer.class));
            lockInsideTx.set(jdbc.queryForObject("SELECT sales_locked_at FROM screenings WHERE id = ?",
                    LocalDateTime.class, s.id));
            throw new IllegalStateException("injected unexpected failure");
        });
        Reply failed = member.post(BASE + "/holds", body, key);
        assertThat(failed.status()).isEqualTo(500);
        assertThat(heldInsideTx).as("domain change was written inside the tx").hasValue(2);
        assertThat(claimsInsideTx).as("claim row was written inside the same tx").hasValue(1);
        assertThat(lockInsideTx.get()).as("sales lock was written inside the tx").isNotNull();
        assertThat(snapshot()).as("all rolled back together").isEqualTo(pristine);

        // (2) 일시적 DB 장애(잠금 실패) → 503 고정 응답, 저장하지 않음, 민감 문자열 비노출.
        hooks.afterDomainWrite.set(jdbc -> {
            throw new CannotAcquireLockException(sensitive);
        });
        Reply unavailable = member.post(BASE + "/holds", body, key);
        assertThat(unavailable.status()).isEqualTo(503);
        assertThat(unavailable.keys()).containsExactlyInAnyOrder("code", "message");
        assertThat((String) unavailable.json("$.code")).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(unavailable.body()).doesNotContain("secret-host").doesNotContain(PASSWORD);
        assertThat(snapshot()).isEqualTo(pristine);

        // (3) 업무 오류(4xx)도 종결 응답으로 저장하지 않는다: 점유 좌석 → 409, 이후 상태가 바뀌면 같은 키로 성공할 수 있다.
        hooks.afterDomainWrite.set(jdbc -> { });
        jdbcTemplate.update("UPDATE screening_seats SET state = 'BOOKED' WHERE screening_id = ? AND seat_id = ?",
                s.id, s.seat(1));
        Reply taken = member.post(BASE + "/holds", body, key);
        assertThat(taken.status()).isEqualTo(409);
        assertThat((String) taken.json("$.code")).isEqualTo("SEAT_UNAVAILABLE");
        assertThat(records(memberId)).isEmpty();
        jdbcTemplate.update("UPDATE screening_seats SET state = 'AVAILABLE' WHERE screening_id = ?", s.id);
        assertThat(snapshot()).isEqualTo(pristine);

        // (4) 같은 키 재시도 → 정상 실행·기록, 이후 재전송.
        Reply retried = member.post(BASE + "/holds", body, key);
        assertThat(retried.status()).isEqualTo(201);
        assertThat(retried.header(IdempotentResponse.REPLAYED_HEADER)).isNull();
        assertThat(seatStates(s.id)).isEqualTo("[HELD, HELD, AVAILABLE]");
        assertThat(salesLockedAt(s.id)).isNotNull();
        assertThat(records(memberId)).singleElement()
                .satisfies(row -> assertThat(row.get("response_body")).isEqualTo(retried.body()));
        assertThat(member.post(BASE + "/holds", body, key).body()).isEqualTo(retried.body());
        assertThat(hooks.domainRuns).as("1 rolled back + 1 rolled back + 1 committed (409 stops before writes)")
                .hasValue(3);

        // DB 제약: 2xx 외 상태·미완료 조합·비JSON 본문·형식 위반 키/operation은 저장할 수 없다.
        String insert = "INSERT INTO idempotency_records (member_id, operation, idempotency_key, request_hash, "
                + "http_status, response_body, created_at, completed_at) VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), ?)";
        String hash = "a".repeat(64);
        LocalDateTime now = LocalDateTime.of(2030, 1, 1, 0, 0);
        List<Object[]> rejected = List.of(
                new Object[] {memberId, "HOLD_CREATE", UUID.randomUUID().toString(), hash, 503, "{}", now},
                new Object[] {memberId, "HOLD_CREATE", UUID.randomUUID().toString(), hash, 409, "{}", now},
                new Object[] {memberId, "HOLD_CREATE", UUID.randomUUID().toString(), hash, 201, null, now},
                new Object[] {memberId, "HOLD_CREATE", UUID.randomUUID().toString(), hash, 201, "{}", null},
                new Object[] {memberId, "HOLD_CREATE", UUID.randomUUID().toString(), hash, 201, "not json", now},
                new Object[] {memberId, "HOLD_CREATE", UUID.randomUUID().toString().toUpperCase(), hash, 201, "{}", now},
                new Object[] {memberId, "LOGIN", UUID.randomUUID().toString(), hash, 201, "{}", now},
                new Object[] {memberId, "HOLD_CREATE", UUID.randomUUID().toString(), "XYZ", 201, "{}", now});
        for (Object[] args : rejected) {
            assertThatThrownBy(() -> jdbcTemplate.update(insert, args)).as(java.util.Arrays.toString(args))
                    .isInstanceOf(DataAccessException.class);
        }
        assertThat(records(memberId)).hasSize(1);
    }

    // AC5 비동기 접수 재전송·최신 상태 별도 조회, 인증 전·타인 리소스 비유출, 비밀 비기록 ------------------------------

    @Test
    void asyncAcceptanceReplaysStablyAndRecordsNeverLeakAcrossAuthenticationOrOwnership() throws Exception {
        long ownerId = signup("idem_owner");
        long otherId = signup("idem_other");
        Browser owner = login("idem_owner");
        Browser other = login("idem_other");
        Screening s = screeningWithSeats("비동기관", 2, ownerId);
        String holdKey = UUID.randomUUID().toString();
        String holdBody = holdBody(s.id, s.seat(0), s.seat(1));
        Reply held = owner.post(BASE + "/holds", holdBody, holdKey);
        assertThat(held.status()).isEqualTo(201);

        // 비동기 접수: 202 {attemptId,statusUrl}. 재전송해도 같은 202·같은 작업 ID.
        String acceptKey = UUID.randomUUID().toString();
        Reply accepted = owner.post(BASE + "/holds/" + s.id + "/payment-attempts", null, acceptKey);
        assertThat(accepted.status()).isEqualTo(202);
        assertThat(accepted.keys()).containsExactly("attemptId", "statusUrl");
        String attemptId = (String) accepted.json("$.attemptId");
        String statusUrl = (String) accepted.json("$.statusUrl");
        assertThat(owner.get(statusUrl).body()).contains("\"BOOKED\"");

        // 접수 뒤 작업 상태가 바뀌어도(작업자 처리 모사) 재전송은 접수 시점의 원래 202이고, 최신 상태는 조회 API로 본다.
        jdbcTemplate.update("UPDATE screening_seats SET state = 'AVAILABLE' WHERE screening_id = ?", s.id);
        for (int i = 0; i < 2; i++) {
            Reply replay = owner.post(BASE + "/holds/" + s.id + "/payment-attempts", null, acceptKey);
            assertThat(replay.status()).isEqualTo(202);
            assertThat(replay.body()).isEqualTo(accepted.body());
            assertThat((String) replay.json("$.attemptId")).isEqualTo(attemptId);
        }
        Reply latest = owner.get(statusUrl);
        assertThat(latest.status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(latest.body(), "$.seats[*].status")).containsOnly("AVAILABLE");
        // 새 키는 새 요청이다: 현재 상태(확보 좌석 없음)로 판정되고 4xx는 기록되지 않는다.
        Reply fresh = owner.post(BASE + "/holds/" + s.id + "/payment-attempts", null, UUID.randomUUID().toString());
        assertThat(fresh.status()).isEqualTo(409);
        assertThat((String) fresh.json("$.code")).isEqualTo("NOTHING_HELD");
        assertThat(records(ownerId)).hasSize(2);
        String before = snapshot();

        // 인증 전: 기록 조회 전에 401. 원래 응답·작업 ID를 담지 않는다.
        Browser anonymous = new Browser();
        anonymous.fetchCsrf();
        for (String[] request : new String[][] {{BASE + "/holds", holdBody, holdKey},
                {BASE + "/holds/" + s.id + "/payment-attempts", null, acceptKey}}) {
            Reply unauthenticated = anonymous.post(request[0], request[1], request[2]);
            assertThat(unauthenticated.status()).isEqualTo(401);
            assertThat(unauthenticated.keys()).containsExactlyInAnyOrder("code", "message");
            assertThat(unauthenticated.body()).doesNotContain(attemptId).doesNotContain((String) held.json("$.token"));
            assertThat(unauthenticated.header(IdempotentResponse.REPLAYED_HEADER)).isNull();
        }
        // 다른 회원이 소유자의 키·경로·본문을 그대로 보내도 자기 범위로 실행된다: 타인 자원은 404, 기록 없음, 유출 없음.
        for (String[] request : new String[][] {{BASE + "/holds", holdBody, holdKey},
                {BASE + "/holds/" + s.id + "/payment-attempts", null, acceptKey},
                {BASE + "/holds/" + s.id + "/release", null, UUID.randomUUID().toString()}}) {
            Reply foreign = other.post(request[0], request[1], request[2]);
            assertThat(foreign.status()).as(request[0]).isEqualTo(404);
            assertThat(foreign.keys()).containsExactlyInAnyOrder("code", "message");
            assertThat((String) foreign.json("$.code")).isEqualTo("PROBE_NOT_FOUND");
            assertThat(foreign.body()).doesNotContain(attemptId).doesNotContain((String) held.json("$.token"));
            assertThat(foreign.header(IdempotentResponse.REPLAYED_HEADER)).isNull();
        }
        assertThat(snapshot()).isEqualTo(before);

        // 자기 범위에 이미 기록이 있는 키로 타인 자원을 대상으로 해도 소유권 검사가 기록 조회·충돌 판정보다 먼저다:
        // 409 IDEMPOTENCY_CONFLICT나 재전송이 아니라 404이며, 기록·도메인·작업 실행 변화가 없다.
        Screening othersOwn = screeningWithSeats("타인 소유관", 2, otherId);
        markHeld(othersOwn.id, null, otherId);
        String otherReleaseKey = UUID.randomUUID().toString();
        Reply otherReleased = other.post(BASE + "/holds/" + othersOwn.id + "/release", null, otherReleaseKey);
        assertThat(otherReleased.status()).isEqualTo(200);
        String otherToken = (String) otherReleased.json("$.token");
        markHeld(othersOwn.id, null, otherId);
        String beforeCross = snapshot();
        int runsBeforeCross = hooks.domainRuns.get();
        List<Reply> crossReplies = List.of(
                other.post(BASE + "/holds/" + s.id + "/release", null, otherReleaseKey),
                owner.post(BASE + "/holds/" + othersOwn.id + "/payment-attempts", null, acceptKey),
                owner.post(BASE + "/holds/" + othersOwn.id + "/release", null, UUID.randomUUID().toString()));
        for (Reply cross : crossReplies) {
            assertThat(cross.status()).isEqualTo(404);
            assertThat(cross.keys()).containsExactlyInAnyOrder("code", "message");
            assertThat((String) cross.json("$.code")).isEqualTo("PROBE_NOT_FOUND");
            assertThat(cross.body()).doesNotContain(attemptId).doesNotContain(otherToken)
                    .doesNotContain((String) held.json("$.token"));
            assertThat(cross.header(IdempotentResponse.REPLAYED_HEADER)).isNull();
        }
        assertThat(snapshot()).isEqualTo(beforeCross);
        assertThat(hooks.domainRuns).hasValue(runsBeforeCross);
        // 같은 키를 자기 원래 경로로 보내면 여전히 원래 응답을 재전송한다.
        Reply otherReplay = other.post(BASE + "/holds/" + othersOwn.id + "/release", null, otherReleaseKey);
        assertThat(otherReplay.status()).isEqualTo(200);
        assertThat(otherReplay.body()).isEqualTo(otherReleased.body());
        assertThat(owner.post(BASE + "/holds/" + s.id + "/payment-attempts", null, acceptKey).body())
                .isEqualTo(accepted.body());
        assertThat(snapshot()).isEqualTo(beforeCross);

        // 저장 대상은 범위·요청 해시·2xx 응답뿐: 열 목록 고정, 비밀번호·로그인 ID·세션 쿠키·CSRF 토큰 비기록.
        assertThat(jdbcTemplate.queryForList("SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'idempotency_records' ORDER BY ordinal_position",
                String.class)).map(String::toLowerCase).containsExactly("id", "member_id", "operation",
                "idempotency_key", "request_hash", "http_status", "response_body", "created_at", "completed_at");
        String stored = jdbcTemplate.queryForList("SELECT * FROM idempotency_records").toString();
        assertThat(stored).doesNotContain(PASSWORD).doesNotContain("idem_owner").doesNotContain("idem_other")
                .doesNotContain(owner.sessionCookie).doesNotContain(owner.csrfToken)
                .doesNotContain(other.sessionCookie).doesNotContain(other.csrfToken)
                .doesNotContain("argon2");
    }

    // probe -----------------------------------------------------------------------------------------------

    /** 테스트 fixture 상태. 소유자 매핑은 변경 대상이 아니라 fixture다. 도메인 변경은 DB에서만 일어난다. */
    static final class ProbeHooks {
        final Map<Long, Long> owners = new ConcurrentHashMap<>();
        final AtomicReference<Consumer<JdbcTemplate>> afterDomainWrite = new AtomicReference<>(jdbc -> { });
        /** 도메인 쓰기까지 도달한 실행 횟수(롤백된 실행 포함). */
        final AtomicInteger domainRuns = new AtomicInteger();

        void reset() {
            owners.clear();
            afterDomainWrite.set(jdbc -> { });
            domainRuns.set(0);
        }
    }

    /**
     * 테스트 전용 멱등 probe. 후속 확보·결제 컨트롤러와 같은 순서(키 → 본문 검증·지문 → 실행기)로 실제 실행기를 호출한다.
     * 경로 ID는 probe 회차 ID다.
     */
    @RestController
    @RequestMapping("/api/v1/test-idempotency")
    static final class ProbeController {
        private final IdempotencyExecutor executor;
        private final JdbcTemplate jdbc;
        private final ProbeHooks hooks;

        ProbeController(IdempotencyExecutor executor, JdbcTemplate jdbc, ProbeHooks hooks) {
            this.executor = executor;
            this.jdbc = jdbc;
            this.hooks = hooks;
        }

        @PostMapping("/holds")
        ResponseEntity<String> hold(@AuthenticationPrincipal AuthenticatedMember member, HttpServletRequest http,
                @RequestBody(required = false) Map<String, Object> body) {
            IdempotencyKey key = IdempotencyKey.from(http);
            RequestValues.requireObject(body, Set.of("screeningId", "seatIds"));
            long screeningId = RequestValues.jsonInteger(body.get("screeningId"), 1, Long.MAX_VALUE,
                    "INVALID_SCREENING_ID", "유효하지 않은 회차 ID입니다.");
            if (!(body.get("seatIds") instanceof List<?> rawSeats) || rawSeats.isEmpty()) {
                throw RequestValues.badRequest("INVALID_SEAT_IDS", "좌석 ID 목록이 필요합니다.");
            }
            List<Long> seatIds = new ArrayList<>();
            for (Object raw : rawSeats) {
                seatIds.add(RequestValues.jsonInteger(raw, 1, Long.MAX_VALUE, "INVALID_SEAT_IDS", "좌석 ID가 올바르지 않습니다."));
            }
            IdempotentRequest request = IdempotentRequest.holdCreate(screeningId, seatIds);
            // 확보 생성은 경로에 기존 소유 자원이 없다(공개 회차). probe의 회차 소유자 fixture 검사는 작업 안에서 한다.
            return executor.execute(member.memberId(), key, request, IdempotencyAccessCheck.NO_TARGET_RESOURCE, () -> {
                lockOwned(member.memberId(), screeningId);
                List<Long> sorted = seatIds.stream().sorted().toList();
                for (long seatId : sorted) {
                    List<String> state = jdbc.queryForList("SELECT state FROM screening_seats "
                            + "WHERE screening_id = ? AND seat_id = ? FOR UPDATE", String.class, screeningId, seatId);
                    if (state.size() != 1 || !state.get(0).equals("AVAILABLE")) {
                        throw new ApiException(HttpStatus.CONFLICT, "SEAT_UNAVAILABLE", "선택할 수 없는 좌석입니다.");
                    }
                }
                long holdId = insertFixtureHold(jdbc, member.memberId(), screeningId);
                for (long seatId : sorted) {
                    jdbc.update("UPDATE screening_seats SET state = 'HELD', hold_id = ? "
                            + "WHERE screening_id = ? AND seat_id = ?", holdId, screeningId, seatId);
                }
                jdbc.update("UPDATE screenings SET sales_locked_at = COALESCE(sales_locked_at, UTC_TIMESTAMP(6)) "
                        + "WHERE id = ?", screeningId);
                afterDomainWrite();
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("screeningId", screeningId);
                response.put("seatIds", sorted);
                response.put("token", UUID.randomUUID().toString());
                response.put("heldAt", ScreeningSchedule.fromDb(
                        jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class)).toString());
                return IdempotentResult.created(response);
            }).toResponseEntity();
        }

        @PostMapping("/holds/{id}/release")
        ResponseEntity<String> release(@AuthenticationPrincipal AuthenticatedMember member, HttpServletRequest http,
                @PathVariable("id") String rawId) {
            IdempotencyKey key = IdempotencyKey.from(http);
            long screeningId = RequestValues.positiveId(rawId, "INVALID_SCREENING_ID", "유효하지 않은 회차 ID입니다.");
            return executor.execute(member.memberId(), key, IdempotentRequest.holdRelease(screeningId),
                    ownedBy(member.memberId(), screeningId), () -> {
                lockOwned(member.memberId(), screeningId);
                int released = jdbc.update("UPDATE screening_seats SET state = 'AVAILABLE', hold_id = NULL "
                        + "WHERE screening_id = ? AND state = 'HELD'", screeningId);
                afterDomainWrite();
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("screeningId", screeningId);
                response.put("released", (long) released);
                response.put("token", UUID.randomUUID().toString());
                return IdempotentResult.ok(response);
            }).toResponseEntity();
        }

        /** 비동기 접수 모사: 확보 좌석을 BOOKED로 바꾸고 202 {attemptId,statusUrl}. 최신 상태는 공개 좌석 조회로 확인한다. */
        @PostMapping("/holds/{id}/payment-attempts")
        ResponseEntity<String> accept(@AuthenticationPrincipal AuthenticatedMember member, HttpServletRequest http,
                @PathVariable("id") String rawId) {
            IdempotencyKey key = IdempotencyKey.from(http);
            long screeningId = RequestValues.positiveId(rawId, "INVALID_SCREENING_ID", "유효하지 않은 회차 ID입니다.");
            return executor.execute(member.memberId(), key, IdempotentRequest.paymentAttemptCreate(screeningId),
                    ownedBy(member.memberId(), screeningId), () -> {
                lockOwned(member.memberId(), screeningId);
                int booked = jdbc.update("UPDATE screening_seats SET state = 'BOOKED', hold_id = NULL "
                        + "WHERE screening_id = ? AND state = 'HELD'", screeningId);
                if (booked == 0) {
                    throw new ApiException(HttpStatus.CONFLICT, "NOTHING_HELD", "확보한 좌석이 없습니다.");
                }
                afterDomainWrite();
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("attemptId", UUID.randomUUID().toString());
                response.put("statusUrl", "/api/v1/screenings/" + screeningId + "/seats");
                return IdempotentResult.accepted(response);
            }).toResponseEntity();
        }

        /** 실행기가 기록 접근 전에 실행하는 소유권 검사: 없는 자원·타인 자원 → 404. 잠금을 잡지 않는다. */
        private IdempotencyAccessCheck ownedBy(long memberId, long screeningId) {
            return () -> requireOwner(memberId, screeningId);
        }

        private void requireOwner(long memberId, long screeningId) {
            Long owner = hooks.owners.get(screeningId);
            if (owner == null || owner != memberId) {
                throw new ApiException(HttpStatus.NOT_FOUND, "PROBE_NOT_FOUND", "대상을 찾을 수 없습니다.");
            }
        }

        /** 도메인 작업 안에서 권한을 재검증(소유자 아님 → 404)하고 회차 행을 잠근다. */
        private void lockOwned(long memberId, long screeningId) {
            requireOwner(memberId, screeningId);
            jdbc.queryForList("SELECT id FROM screenings WHERE id = ? FOR UPDATE", Long.class, screeningId);
        }

        private void afterDomainWrite() {
            hooks.domainRuns.incrementAndGet();
            hooks.afterDomainWrite.get().accept(jdbc);
        }
    }

    /**
     * 승자 트랜잭션 안에서, 다른 세션 {@code expected}개가 같은 키 선점 INSERT를 실행 중(=고유 키 잠금 대기)인지
     * processlist로 확인한다. 유한 시간 안에 도달하지 못하면 실패한다.
     */
    private static int awaitClaimWaiters(JdbcTemplate jdbc, int expected) {
        long deadline = System.nanoTime() + WAITER_GATE_TIMEOUT.toNanos();
        int waiting = 0;
        while (System.nanoTime() < deadline) {
            waiting = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.processlist "
                    + "WHERE id <> CONNECTION_ID() AND info LIKE 'INSERT INTO idempotency_records%'", Integer.class);
            if (waiting >= expected) {
                return waiting;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("expected " + expected + " claim waiters, saw " + waiting);
    }

    // helpers -----------------------------------------------------------------------------------------

    private record Screening(long id, List<Long> seatIds) {
        long seat(int index) {
            return seatIds.get(index);
        }
    }

    private Screening screeningWithSeats(String theaterName, int seats, long ownerId) {
        long movie = insert("INSERT INTO movies (title, runtime_minutes, age_rating) VALUES (?, ?, ?)",
                theaterName + " 영화", 100, "ALL");
        long theater = insert("INSERT INTO theaters (name) VALUES (?)", theaterName);
        List<Long> seatIds = new ArrayList<>();
        for (int number = 1; number <= seats; number++) {
            seatIds.add(insert("INSERT INTO seats (theater_id, row_label, seat_number) VALUES (?, 'A', ?)",
                    theater, number));
        }
        Instant start = Instant.parse("2031-06-01T01:00:00Z");
        long id = insert("INSERT INTO screenings (movie_id, theater_id, starts_at, ends_at, base_price) "
                        + "VALUES (?, ?, ?, ?, ?)", movie, theater, ScreeningSchedule.toDb(start),
                ScreeningSchedule.toDb(ScreeningSchedule.endsAt(start, 100)), 12000);
        jdbcTemplate.update("INSERT INTO screening_seats (screening_id, theater_id, seat_id, state) "
                + "SELECT ?, theater_id, id, 'AVAILABLE' FROM seats WHERE theater_id = ?", id, theater);
        hooks.owners.put(id, ownerId);
        return new Screening(id, seatIds);
    }

    /**
     * HJ-006부터 HELD 좌석은 같은 회차의 확보 행을 참조해야 한다(DB CHECK·FK). probe는 확보 기능이 아니라 멱등 실행기를 시험하므로,
     * 좌석을 HELD로 표시할 때 소유 확보 fixture 행을 함께 만든다. 시각은 probe 회차 시작(2031-06-01T01:00Z) 직전 5분 창이다.
     */
    static long insertFixtureHold(JdbcTemplate jdbc, long memberId, long screeningId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO holds (member_id, screening_id, status, total_amount, expires_at, created_at) "
                            + "VALUES (?, ?, 'ACTIVE', 0, '2031-06-01 01:00:00', '2031-06-01 00:55:00')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, memberId);
            statement.setLong(2, screeningId);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 좌석(seatId가 null이면 회차 전체)을 owner의 fixture 확보로 HELD 표시한다. */
    private void markHeld(long screeningId, Long seatId, long ownerId) {
        long holdId = insertFixtureHold(jdbcTemplate, ownerId, screeningId);
        if (seatId == null) {
            jdbcTemplate.update("UPDATE screening_seats SET state = 'HELD', hold_id = ? WHERE screening_id = ?",
                    holdId, screeningId);
        } else {
            jdbcTemplate.update("UPDATE screening_seats SET state = 'HELD', hold_id = ? "
                    + "WHERE screening_id = ? AND seat_id = ?", holdId, screeningId, seatId);
        }
    }

    private String seatStates(long screeningId) {
        return jdbcTemplate.queryForList("SELECT state FROM screening_seats WHERE screening_id = ? ORDER BY seat_id",
                String.class, screeningId).toString();
    }

    private LocalDateTime salesLockedAt(long screeningId) {
        return jdbcTemplate.queryForObject("SELECT sales_locked_at FROM screenings WHERE id = ?", LocalDateTime.class,
                screeningId);
    }

    private List<Map<String, Object>> records(long memberId) {
        return jdbcTemplate.queryForList("SELECT * FROM idempotency_records WHERE member_id = ? ORDER BY id",
                memberId);
    }

    /** 도메인(회차 판매 잠금·좌석 상태)과 멱등 기록 전체의 사후 상태. */
    private String snapshot() {
        return jdbcTemplate.queryForList("SELECT id, sales_locked_at FROM screenings ORDER BY id")
                + "|" + jdbcTemplate.queryForList("SELECT screening_id, seat_id, state FROM screening_seats "
                + "ORDER BY screening_id, seat_id")
                + "|" + jdbcTemplate.queryForList("SELECT * FROM idempotency_records ORDER BY id");
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

    private record Reply(int status, String body, Map<String, List<String>> headers) {
        Object json(String path) {
            return JsonPath.read(body, path);
        }

        List<String> keys() {
            Map<String, Object> object = JsonPath.read(body, "$");
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
        String sessionCookie;
        String csrfToken;
        String csrfHeader = "X-CSRF-TOKEN";

        Browser copy() {
            Browser copy = new Browser();
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
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
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
