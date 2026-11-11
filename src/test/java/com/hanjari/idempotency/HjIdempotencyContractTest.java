package com.hanjari.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.hanjari.common.ApiException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * HJ-005 멱등성 계약의 DB 없는 단위 검증: 키 형식, 요청 지문 정규화(좌석 순서·중복 거부·경로·operation), 저장 대상,
 * 실행기의 선점·재전송·충돌·범위·롤백 규칙. 실제 MySQL 고유 제약·동시성·도메인 동시 롤백은 {@code HjIdempotencyPersistenceIT}가 검증한다.
 */
class HjIdempotencyContractTest {

    private static final String KEY = "3f2b8c1e-9a4d-4e7f-b0c2-1d5e6f7a8b9c";

    // 키 형식 ------------------------------------------------------------------------------------------

    @Test
    void idempotencyKeyAcceptsOnlyOneCanonicalUuidHeaderAndLowercasesHex() {
        assertThat(IdempotencyKey.fromHeaderValues(List.of(KEY)).value()).isEqualTo(KEY);
        assertThat(IdempotencyKey.fromHeaderValues(List.of(KEY.toUpperCase())))
                .as("UUID hex is case-insensitive").isEqualTo(new IdempotencyKey(KEY));

        ApiException missing = catchThrowableOfType(ApiException.class,
                () -> IdempotencyKey.fromHeaderValues(List.of()));
        assertThat(missing.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getCode()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(catchThrowableOfType(ApiException.class, () -> IdempotencyKey.fromHeaderValues(null)).getCode())
                .isEqualTo("IDEMPOTENCY_KEY_REQUIRED");

        List<String> invalid = List.of("", " ", " " + KEY, KEY + " ", "{" + KEY + "}", "urn:uuid:" + KEY,
                KEY.replace("-", ""), KEY.substring(1), KEY + "0", KEY.replace('a', 'g'), "not-a-uuid",
                KEY + "," + KEY, KEY.replace("-", "_"), "3f2b8c1e9-a4d-4e7f-b0c2-1d5e6f7a8b9c");
        for (String value : invalid) {
            ApiException rejected = catchThrowableOfType(ApiException.class,
                    () -> IdempotencyKey.fromHeaderValues(List.of(value)));
            assertThat(rejected).as("[%s]", value).isNotNull();
            assertThat(rejected.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(rejected.getCode()).isEqualTo("INVALID_IDEMPOTENCY_KEY");
            assertThat(rejected.getMessage()).doesNotContain(KEY).doesNotContain("not-a-uuid");
        }
        // 같은 헤더가 두 번 오면 어느 쪽인지 고르지 않고 거부한다.
        assertThat(catchThrowableOfType(ApiException.class,
                () -> IdempotencyKey.fromHeaderValues(List.of(KEY, KEY))).getCode())
                .isEqualTo("INVALID_IDEMPOTENCY_KEY");
        // 저장 형식은 소문자 정규형만 허용하고, toString은 값을 노출하지 않는다.
        assertThatThrownBy(() -> new IdempotencyKey(KEY.toUpperCase())).isInstanceOf(IllegalArgumentException.class);
        assertThat(new IdempotencyKey(KEY).toString()).doesNotContain(KEY);
    }

    // 요청 지문 ----------------------------------------------------------------------------------------

    @Test
    void holdCreateFingerprintIsIndependentOfSeatOrderButNotOfContent() {
        IdempotentRequest base = IdempotentRequest.holdCreate(7, List.of(30L, 10L, 20L));
        for (List<Long> permutation : List.of(List.of(10L, 20L, 30L), List.of(20L, 30L, 10L), List.of(30L, 20L, 10L))) {
            IdempotentRequest same = IdempotentRequest.holdCreate(7, permutation);
            assertThat(same.hash()).as("%s", permutation).isEqualTo(base.hash());
            assertThat(same.canonical()).isEqualTo(base.canonical());
        }
        assertThat(base.operation()).isEqualTo(IdempotencyOperation.HOLD_CREATE);
        assertThat(base.canonical()).isEqualTo("{\"body\":{\"screeningId\":7,\"seatIds\":[10,20,30]},"
                + "\"method\":\"POST\",\"operation\":\"HOLD_CREATE\",\"path\":\"/api/v1/holds\"}");
        assertThat(base.hash()).matches("[0-9a-f]{64}").isEqualTo(IdempotentRequest.sha256(base.canonical()));

        // 내용이 다르면 지문이 다르다: 좌석 추가·제거·교체, 다른 회차, 숫자 경계(10과 1,0 구분).
        List<IdempotentRequest> different = List.of(
                IdempotentRequest.holdCreate(7, List.of(10L, 20L)),
                IdempotentRequest.holdCreate(7, List.of(10L, 20L, 30L, 40L)),
                IdempotentRequest.holdCreate(7, List.of(10L, 20L, 31L)),
                IdempotentRequest.holdCreate(8, List.of(10L, 20L, 30L)),
                IdempotentRequest.holdCreate(71, List.of(0L, 20L, 30L)),
                IdempotentRequest.holdCreate(7, List.of(1020L, 30L)));
        assertThat(different).extracting(IdempotentRequest::hash).doesNotContain(base.hash()).doesNotHaveDuplicates();
    }

    @Test
    void duplicateSeatIdsAreRejectedInsteadOfBeingNormalizedAway() {
        for (List<Long> seats : List.of(List.of(1L, 1L), List.of(5L, 2L, 5L), List.of(9L, 9L, 9L, 9L))) {
            ApiException rejected = catchThrowableOfType(ApiException.class,
                    () -> IdempotentRequest.holdCreate(3, seats));
            assertThat(rejected).as("%s", seats).isNotNull();
            assertThat(rejected.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(rejected.getCode()).isEqualTo("DUPLICATE_SEAT_ID");
        }
        // 중복을 제거한 집합과 같은 지문이 되는 일은 없다(중복 요청은 지문 자체가 만들어지지 않는다).
        // null 원소·null 목록은 입력 검증을 건너뛴 호출자 오류다.
        assertThatThrownBy(() -> IdempotentRequest.holdCreate(3, Arrays.asList(1L, null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> IdempotentRequest.holdCreate(3, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void pathAndOperationArePartOfTheFingerprintAndPathsMustBeCanonical() {
        IdempotentRequest release1 = IdempotentRequest.holdRelease(1);
        assertThat(release1.canonical()).isEqualTo("{\"body\":{},\"method\":\"POST\",\"operation\":\"HOLD_RELEASE\","
                + "\"path\":\"/api/v1/holds/1/release\"}");
        List<IdempotentRequest> all = List.of(release1, IdempotentRequest.holdRelease(2),
                IdempotentRequest.holdRelease(11), IdempotentRequest.paymentAttemptCreate(1),
                IdempotentRequest.paymentAttemptCreate(2), IdempotentRequest.reservationCancel(1));
        assertThat(all).extracting(IdempotentRequest::hash).doesNotHaveDuplicates();
        assertThat(IdempotentRequest.paymentAttemptCreate(1).operation())
                .isEqualTo(IdempotencyOperation.PAYMENT_ATTEMPT_CREATE);
        assertThat(IdempotentRequest.reservationCancel(1).canonical())
                .contains("\"path\":\"/api/v1/reservations/1/cancellations\"");
        // 지문 원문에는 선언한 필드만 있다(헤더·쿠키·세션 정보 없음).
        assertThat(release1.canonical()).doesNotContainIgnoringCase("cookie").doesNotContainIgnoringCase("csrf")
                .doesNotContainIgnoringCase("session").doesNotContainIgnoringCase("authorization");
        assertThat(release1.toString()).doesNotContain(release1.hash());
        // SHA-256 계산 확인(표준 벡터).
        assertThat(IdempotentRequest.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");

        // 원시 표기(인코딩·matrix·대문자·끝 슬래시)는 지문 경로가 될 수 없다. 조회 메서드는 대상이 아니다.
        for (String path : List.of("/api/v1/holds/%31/release", "/api/v1/holds/1;x=1/release", "/api/v1/Holds",
                "/api/v1/holds/", "/api/v1/holds//1", "/api/v1/holds/../me", "/api/v2/holds", "api/v1/holds")) {
            assertThatThrownBy(() -> IdempotentRequest.builder(IdempotencyOperation.HOLD_CREATE, "POST", path))
                    .as(path).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> IdempotentRequest.builder(IdempotencyOperation.HOLD_CREATE, "GET", "/api/v1/holds"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotentRequest.holdRelease(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotentRequest.builder(IdempotencyOperation.HOLD_CREATE, "POST", "/api/v1/holds")
                .number("a", 1).number("a", 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyChangeOperationsAreRecordedAndOnlyFinalSuccessResponsesAreStorable() {
        // 가입·로그인·로그아웃(비밀번호·세션 요청)은 멱등 기록 대상이 아니다.
        assertThat(IdempotencyOperation.values()).extracting(Enum::name).containsExactly(
                "HOLD_CREATE", "HOLD_RELEASE", "PAYMENT_ATTEMPT_CREATE", "RESERVATION_CANCEL");
        // 종결 응답으로 저장하는 것은 2xx뿐이다(일시적 5xx·업무 오류는 롤백 후 재실행).
        for (int status : new int[] {199, 300, 400, 404, 409, 500, 503}) {
            assertThatThrownBy(() -> new IdempotentResult(status, Map.of())).as("%d", status)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(IdempotentResult.accepted(Map.of()).status()).isEqualTo(202);
        assertThat(IdempotentResult.created(Map.of()).status()).isEqualTo(201);
        assertThat(IdempotentResult.ok(Map.of()).status()).isEqualTo(200);

        // 저장 본문 직렬화는 결정적이며 표현이 흔들리는 값(실수 등)은 거부한다.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attemptId", 42L);
        body.put("statusUrl", "/api/v1/payment-attempts/42");
        body.put("note", "따옴표\" 역슬래시\\ 줄바꿈\n");
        body.put("items", List.of(1, true));
        body.put("none", null);
        assertThat(CanonicalJson.write(body)).isEqualTo("{\"attemptId\":42,\"statusUrl\":\"/api/v1/payment-attempts/42\","
                + "\"note\":\"따옴표\\\" 역슬래시\\\\ 줄바꿈\\n\",\"items\":[1,true],\"none\":null}");
        assertThatThrownBy(() -> CanonicalJson.write(Map.of("amount", 1.5))).isInstanceOf(IllegalArgumentException.class);

        // 재전송은 상태·본문이 같고 표시 헤더만 추가된다.
        ResponseEntity<String> fresh = new IdempotentResponse(202, "{\"a\":1}", false).toResponseEntity();
        ResponseEntity<String> replay = new IdempotentResponse(202, "{\"a\":1}", true).toResponseEntity();
        assertThat(replay.getStatusCode().value()).isEqualTo(fresh.getStatusCode().value());
        assertThat(replay.getBody()).isEqualTo(fresh.getBody());
        assertThat(fresh.getHeaders().containsHeader(IdempotentResponse.REPLAYED_HEADER)).isFalse();
        assertThat(replay.getHeaders().getFirst(IdempotentResponse.REPLAYED_HEADER)).isEqualTo("true");
    }

    // 실행기 -------------------------------------------------------------------------------------------

    @Test
    void executorStoresFirstSuccessAndReplaysOriginalWithoutRunningTheActionAgain() {
        Fixture f = new Fixture();
        AtomicInteger runs = new AtomicInteger();
        Supplier<IdempotentResult> action = () -> IdempotentResult.created(
                Map.of("holdId", 100L + runs.incrementAndGet()));
        IdempotentRequest request = IdempotentRequest.holdCreate(1, List.of(2L, 1L));

        IdempotentResponse first = f.executor.execute(5, key(KEY), request, IdempotencyAccessCheck.NO_TARGET_RESOURCE,
                action);
        assertThat(first).isEqualTo(new IdempotentResponse(201, "{\"holdId\":101}", false));
        assertThat(f.tx.lastIsolation).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);

        // 같은 키(대소문자만 다른 표기 포함)·좌석 순서만 다른 같은 요청 → 원래 응답, 작업 미실행.
        IdempotentResponse replay = f.executor.execute(5, IdempotencyKey.fromHeaderValues(List.of(KEY.toUpperCase())),
                IdempotentRequest.holdCreate(1, List.of(1L, 2L)), IdempotencyAccessCheck.NO_TARGET_RESOURCE, action);
        assertThat(replay).isEqualTo(new IdempotentResponse(201, "{\"holdId\":101}", true));
        assertThat(runs).hasValue(1);
        assertThat(f.store.committed).hasSize(1);
        assertThat(f.tx.commits).isEqualTo(2);
        assertThat(f.tx.rollbacks).isZero();
    }

    @Test
    void executorRejectsDifferentRequestUnderSameScopeButSeparatesMembersAndOperations() {
        Fixture f = new Fixture();
        AtomicInteger runs = new AtomicInteger();
        Supplier<IdempotentResult> action = () -> IdempotentResult.ok(Map.of("run", (long) runs.incrementAndGet()));
        f.executor.execute(5, key(KEY), IdempotentRequest.holdRelease(1), OWNED, action);

        // 같은 회원·operation·키 + 다른 경로(자기 리소스) → 409. 원래 응답은 담지 않는다.
        ApiException conflict = catchThrowableOfType(ApiException.class,
                () -> f.executor.execute(5, key(KEY), IdempotentRequest.holdRelease(2), OWNED, action));
        assertThat(conflict.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(conflict.getMessage()).isEqualTo(IdempotencyExecutor.CONFLICT_MESSAGE).doesNotContain("run");
        assertThat(runs).hasValue(1);

        // 소유권 검사는 기록 선점·조회·재전송·충돌 판정보다 먼저다: 자기 범위에 기록이 있는 키로 타인 리소스를 대상으로 하면
        // (다른 경로든 같은 경로든) 409·재전송이 아니라 검사가 던진 404이며, 기록에 접근하지 않고 작업도 실행하지 않는다.
        List<String> order = new ArrayList<>();
        f.store.accesses = order;
        IdempotencyAccessCheck foreign = () -> {
            order.add("access");
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "대상을 찾을 수 없습니다.");
        };
        for (IdempotentRequest target : List.of(IdempotentRequest.holdRelease(2), IdempotentRequest.holdRelease(1))) {
            ApiException hidden = catchThrowableOfType(ApiException.class,
                    () -> f.executor.execute(5, key(KEY), target, foreign, action));
            assertThat(hidden.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(hidden.getCode()).isEqualTo("NOT_FOUND");
        }
        assertThat(order).containsExactly("access", "access");
        f.store.accesses = null;
        assertThat(runs).hasValue(1);
        assertThat(f.store.pending).isEmpty();
        // 소유 리소스 대상 작업은 소유권 검사 생략(NO_TARGET_RESOURCE)·null 검사를 받지 않는다.
        for (IdempotentRequest owned : List.of(IdempotentRequest.holdRelease(1), IdempotentRequest.paymentAttemptCreate(1),
                IdempotentRequest.reservationCancel(1))) {
            assertThatThrownBy(() -> f.executor.execute(5, key(KEY), owned, IdempotencyAccessCheck.NO_TARGET_RESOURCE,
                    action)).as("%s", owned.operation()).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> f.executor.execute(5, key(KEY), owned, null, action))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(IdempotencyOperation.HOLD_CREATE.targetsOwnedResource()).isFalse();
        assertThat(runs).hasValue(1);

        // 다른 회원의 같은 키, 같은 회원의 다른 operation은 서로 독립된 범위다(충돌·재전송 없음).
        assertThat(f.executor.execute(6, key(KEY), IdempotentRequest.holdRelease(1), OWNED, action))
                .isEqualTo(new IdempotentResponse(200, "{\"run\":2}", false));
        assertThat(f.executor.execute(5, key(KEY), IdempotentRequest.paymentAttemptCreate(1), OWNED, action))
                .isEqualTo(new IdempotentResponse(200, "{\"run\":3}", false));
        assertThat(f.store.committed).hasSize(3);
        // 원래 범위의 기록은 그대로다.
        assertThat(f.executor.execute(5, key(KEY), IdempotentRequest.holdRelease(1), OWNED, action))
                .isEqualTo(new IdempotentResponse(200, "{\"run\":1}", true));
    }

    @Test
    void executorRollsBackFailuresWithoutStoringThemSoTheSameKeyCanRetry() {
        Fixture f = new Fixture();
        IdempotentRequest request = IdempotentRequest.reservationCancel(9);
        String sensitive = "jdbc:mysql://db/hanjari_test password=s3cret " + KEY;

        // 업무 오류(4xx)는 그대로 전달되고 저장되지 않는다.
        ApiException business = catchThrowableOfType(ApiException.class, () -> f.executor.execute(5, key(KEY), request,
                OWNED, () -> {
                    throw new ApiException(HttpStatus.CONFLICT, "STATE_CONFLICT", "상태 충돌");
                }));
        assertThat(business.getCode()).isEqualTo("STATE_CONFLICT");
        // 일시적 DB 장애 → 503 고정 응답(원인·민감 문자열 없음), 저장 안 됨.
        ApiException transientFailure = catchThrowableOfType(ApiException.class,
                () -> f.executor.execute(5, key(KEY), request, OWNED, () -> {
                    throw new CannotAcquireLockException(sensitive);
                }));
        assertThat(transientFailure.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(transientFailure.getCode()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(transientFailure.getMessage()).doesNotContain("s3cret").doesNotContain("jdbc");
        assertThat(transientFailure.getCause()).isNull();
        // 그 밖의 예외는 그대로 전달(500 처리기 몫), 역시 저장 안 됨.
        IllegalStateException unexpected = new IllegalStateException("boom");
        assertThatThrownBy(() -> f.executor.execute(5, key(KEY), request, OWNED, () -> {
            throw unexpected;
        })).isSameAs(unexpected);
        assertThat(f.store.committed).isEmpty();
        assertThat(f.tx.rollbacks).isEqualTo(3);

        // 같은 키 재시도는 다시 실행되어 성공을 기록한다.
        IdempotentResponse retried = f.executor.execute(5, key(KEY), request, OWNED,
                () -> IdempotentResult.accepted(Map.of("cancellationId", 1L)));
        assertThat(retried).isEqualTo(new IdempotentResponse(202, "{\"cancellationId\":1}", false));
        assertThat(f.store.committed).hasSize(1);

        // 이미 열린 트랜잭션 안에서는 실행하지 않는다(기록과 도메인이 같은 경계로 커밋돼야 하므로).
        assertThatThrownBy(() -> f.executor.execute(5, key(KEY), IdempotentRequest.holdRelease(3), OWNED,
                () -> {
                    f.executor.execute(5, key("00000000-0000-4000-8000-000000000001"),
                            IdempotentRequest.holdRelease(4), OWNED, () -> IdempotentResult.ok(Map.of()));
                    return IdempotentResult.ok(Map.of());
                })).isInstanceOf(IllegalStateException.class);
        assertThat(f.store.committed).hasSize(1);
    }

    // fixtures -----------------------------------------------------------------------------------------

    /** 소유권 검사를 통과하는 접근 검사(NO_TARGET_RESOURCE와 다른 인스턴스). */
    private static final IdempotencyAccessCheck OWNED = () -> { };

    private static IdempotencyKey key(String value) {
        return IdempotencyKey.fromHeaderValues(List.of(value));
    }

    private static final class Fixture {
        final FakeStore store = new FakeStore();
        final RecordingTransactionManager tx = new RecordingTransactionManager(store);
        final IdempotencyExecutor executor = new IdempotencyExecutor(store, tx);
    }

    /** 트랜잭션 커밋 시에만 반영되는 메모리 저장소. 고유 범위는 Scope record의 equals로 판정한다. */
    private static final class FakeStore implements IdempotencyRecordStore {
        final Map<Scope, StoredRecord> committed = new HashMap<>();
        final Map<Scope, StoredRecord> pending = new HashMap<>();
        /** null이 아니면 저장소 접근(선점·조회·완료)을 순서대로 남긴다. */
        List<String> accesses;

        private void touch(String event) {
            if (accesses != null) {
                accesses.add(event);
            }
        }

        @Override
        public boolean claim(Scope scope, String requestHash) {
            touch("claim");
            if (committed.containsKey(scope) || pending.containsKey(scope)) {
                return false;
            }
            pending.put(scope, new StoredRecord(requestHash, null, null));
            return true;
        }

        @Override
        public Optional<StoredRecord> find(Scope scope) {
            touch("find");
            return Optional.ofNullable(committed.get(scope));
        }

        @Override
        public void complete(Scope scope, int httpStatus, String responseBody) {
            touch("complete");
            StoredRecord claimed = pending.get(scope);
            pending.put(scope, new StoredRecord(claimed.requestHash(), httpStatus, responseBody));
        }

        void commit() {
            committed.putAll(pending);
            pending.clear();
        }

        void rollback() {
            pending.clear();
        }
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private final FakeStore store;
        final List<String> events = new ArrayList<>();
        int commits;
        int rollbacks;
        int lastIsolation;

        RecordingTransactionManager(FakeStore store) {
            this.store = store;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            lastIsolation = definition.getIsolationLevel();
            events.add("begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
            store.commit();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
            store.rollback();
        }
    }
}
