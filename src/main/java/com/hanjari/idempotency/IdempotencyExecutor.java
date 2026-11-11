package com.hanjari.idempotency;

import com.hanjari.common.ApiException;
import com.hanjari.common.AuthFailurePolicy;
import com.hanjari.common.TransientDataAccessFailures;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 변경 요청 멱등 실행기(HJ-005).
 *
 * <p>한 트랜잭션(READ COMMITTED) 안에서 다음 순서로 실행한다.
 * <ol>
 *   <li>접근 검사({@link IdempotencyAccessCheck})를 실행한다. 경로의 리소스가 요청 회원 소유가 아니면 404로 끝나며,
 *       기록을 선점·조회하지 않으므로 같은 키의 기존 기록 존재·응답·충돌 여부가 드러나지 않는다.</li>
 *   <li>범위(회원+operation+키)를 INSERT로 선점한다. 같은 범위의 미커밋 선점이 있으면 DB가 그 트랜잭션 종료까지 기다리게 한다.</li>
 *   <li>선점 실패(이미 커밋된 기록) → 요청 해시가 같으면 저장된 원래 상태·본문을 그대로 재전송하고 작업은 실행하지 않는다.
 *       다르면 409 IDEMPOTENCY_CONFLICT(원래 응답은 담지 않는다).</li>
 *   <li>선점 성공 → 도메인 작업 실행 → 2xx 결과 직렬화 → 기록 완료 → 도메인 변경과 함께 커밋.</li>
 * </ol>
 * 작업이 예외(4xx 업무 오류·5xx 장애)를 던지면 도메인 변경과 선점 기록이 함께 롤백되므로 같은 키로 재실행할 수 있다.
 * 일시적 DB 장애(데드락·잠금 대기 초과·연결 실패)는 503 SERVICE_UNAVAILABLE로 바꾼다(같은 키 재시도 안내). 종결 응답으로
 * 저장하는 것은 2xx뿐이다.
 *
 * <p>호출자는 인증된 회원 ID만 넘긴다(미인증 요청은 보안 체인이 컨트롤러 전에 401로 끝낸다). 범위에 회원이 들어가므로
 * 다른 회원의 기록은 조회·재전송되지 않는다. 키·요청 값·응답 본문은 로그에 남기지 않는다.
 *
 * <p>이 실행기가 트랜잭션 경계를 소유해야 도메인 변경과 기록이 함께 커밋·롤백된다. 이미 진행 중인 트랜잭션 안에서
 * 호출하면 거부한다. 작업 안의 서비스 호출(@Transactional REQUIRED)은 이 트랜잭션에 참여한다.
 */
@Service
public class IdempotencyExecutor {

    public static final String CONFLICT_CODE = "IDEMPOTENCY_CONFLICT";
    public static final String CONFLICT_MESSAGE = "같은 Idempotency-Key로 다른 요청을 보낼 수 없습니다.";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyExecutor.class);

    private final IdempotencyRecordStore store;
    private final TransactionTemplate transactionTemplate;

    public IdempotencyExecutor(IdempotencyRecordStore store, PlatformTransactionManager transactionManager) {
        this.store = store;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * @param accessCheck 기록 접근 전 실행할 소유권 검사. 소유 리소스 대상 작업
     *     ({@link IdempotencyOperation#targetsOwnedResource()})에는 실제 검사가 필수이며
     *     {@link IdempotencyAccessCheck#NO_TARGET_RESOURCE}는 거부한다.
     */
    public IdempotentResponse execute(long memberId, IdempotencyKey key, IdempotentRequest request,
            IdempotencyAccessCheck accessCheck, Supplier<IdempotentResult> action) {
        if (memberId <= 0 || key == null || request == null || accessCheck == null || action == null) {
            throw new IllegalArgumentException("member, key, request, access check and action are required");
        }
        if (request.operation().targetsOwnedResource() && accessCheck == IdempotencyAccessCheck.NO_TARGET_RESOURCE) {
            throw new IllegalArgumentException("operations on owned resources require an ownership check");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("idempotent execution must own the transaction boundary");
        }
        IdempotencyRecordStore.Scope scope = new IdempotencyRecordStore.Scope(memberId, request.operation(), key);
        try {
            return transactionTemplate.execute(status -> run(scope, request, accessCheck, action));
        } catch (ApiException businessOutcome) {
            throw businessOutcome;
        } catch (RuntimeException failure) {
            if (TransientDataAccessFailures.isTransient(failure)) {
                log.warn("event=IDEMPOTENT_REQUEST_ROLLED_BACK operation={} category=DB_TRANSIENT status=503",
                        request.operation());
                throw unavailable();
            }
            throw failure;
        }
    }

    private IdempotentResponse run(IdempotencyRecordStore.Scope scope, IdempotentRequest request,
            IdempotencyAccessCheck accessCheck, Supplier<IdempotentResult> action) {
        // 소유권 검사가 먼저다: 타인·없는 리소스는 기록 선점·조회·재전송·충돌 판정 전에 404로 끝난다(AC5).
        accessCheck.verify();
        if (!store.claim(scope, request.hash())) {
            IdempotencyRecordStore.StoredRecord existing = store.find(scope).orElse(null);
            if (existing == null || !existing.completed()) {
                // 선점 실패는 커밋된 완료 기록이 있을 때만 생긴다. 보이지 않으면 재시도 가능한 일시 상태로 취급한다.
                log.warn("event=IDEMPOTENCY_RECORD_UNSETTLED operation={} status=503", request.operation());
                throw unavailable();
            }
            if (!existing.requestHash().equals(request.hash())) {
                log.info("event=IDEMPOTENCY_CONFLICT operation={} status=409", request.operation());
                throw new ApiException(HttpStatus.CONFLICT, CONFLICT_CODE, CONFLICT_MESSAGE);
            }
            log.info("event=IDEMPOTENCY_REPLAYED operation={} status={}", request.operation(), existing.httpStatus());
            return new IdempotentResponse(existing.httpStatus(), existing.responseBody(), true);
        }
        IdempotentResult result = action.get();
        if (result == null) {
            throw new IllegalStateException("idempotent action returned no result");
        }
        String body = CanonicalJson.write(result.body());
        store.complete(scope, result.status(), body);
        return new IdempotentResponse(result.status(), body, false);
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, AuthFailurePolicy.UNAVAILABLE_CODE,
                AuthFailurePolicy.UNAVAILABLE_MESSAGE);
    }
}
