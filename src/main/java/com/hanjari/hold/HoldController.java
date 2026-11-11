package com.hanjari.hold;

import com.hanjari.auth.AuthenticatedMember;
import com.hanjari.common.RequestValues;
import com.hanjari.idempotency.IdempotencyAccessCheck;
import com.hanjari.idempotency.IdempotencyExecutor;
import com.hanjari.idempotency.IdempotencyKey;
import com.hanjari.idempotency.IdempotentRequest;
import com.hanjari.idempotency.IdempotentResult;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 좌석 확보 API(HJ-006). 보안 체인이 Origin → CSRF → 인증(미인증 401)을 컨트롤러 전에 처리한다.
 *
 * <p>변경 요청의 처리 순서(HJ-005 계약): 키 해석 → 본문 검증·지문 → 멱등 실행기(소유권 검사 → 선점 → 작업 → 기록) → 응답.
 * 타인·없는 확보는 404 HOLD_NOT_FOUND이며 멱등 기록·결과를 드러내지 않는다.
 */
@RestController
@RequestMapping(value = "/api/v1/holds", produces = MediaType.APPLICATION_JSON_VALUE)
public class HoldController {

    private final HoldService holdService;
    private final IdempotencyExecutor executor;

    public HoldController(HoldService holdService, IdempotencyExecutor executor) {
        this.holdService = holdService;
        this.executor = executor;
    }

    /** 201 {holdId,status,seatIds,expiresAt,totalAmount,serverNow}. 같은 키 재전송은 원래 응답(만료 시각 포함) 그대로다. */
    @PostMapping
    public ResponseEntity<String> create(@AuthenticationPrincipal AuthenticatedMember member, HttpServletRequest http,
            @RequestBody(required = false) Map<String, Object> body) {
        IdempotencyKey key = IdempotencyKey.from(http);
        HoldRequestPolicy.HoldCreate request = HoldRequestPolicy.holdCreate(body);
        IdempotentRequest fingerprint = request.fingerprint();
        long memberId = member.memberId();
        // 경로 대상 리소스는 없다. 접근 검사 단계에서 회원 행을 먼저 잠가 선점 INSERT(회원 FK 공유 잠금)보다 앞선다.
        IdempotencyAccessCheck lockMemberFirst = () -> holdService.lockRequestingMember(memberId);
        return executor.execute(memberId, key, fingerprint, lockMemberFirst,
                () -> IdempotentResult.created(holdService.create(memberId, request))).toResponseEntity();
    }

    /** 200 {holdId,status}: 종결 상태(RELEASED 또는 이미 지난 경우 EXPIRED). CONSUMED는 409. */
    @PostMapping("/{holdId}/release")
    public ResponseEntity<String> release(@AuthenticationPrincipal AuthenticatedMember member, HttpServletRequest http,
            @PathVariable("holdId") String rawHoldId) {
        IdempotencyKey key = IdempotencyKey.from(http);
        long holdId = holdId(rawHoldId);
        long memberId = member.memberId();
        return executor.execute(memberId, key, IdempotentRequest.holdRelease(holdId),
                () -> holdService.requireOwned(memberId, holdId),
                () -> IdempotentResult.ok(holdService.release(memberId, holdId))).toResponseEntity();
    }

    /** 본인 유효 확보 또는 204. */
    @GetMapping("/current")
    public ResponseEntity<HoldService.HoldView> current(@AuthenticationPrincipal AuthenticatedMember member) {
        return holdService.current(member.memberId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{holdId}")
    public HoldService.HoldView view(@AuthenticationPrincipal AuthenticatedMember member,
            @PathVariable("holdId") String rawHoldId) {
        return holdService.view(member.memberId(), holdId(rawHoldId));
    }

    private static long holdId(String raw) {
        return RequestValues.positiveId(raw, "INVALID_HOLD_ID", "유효하지 않은 확보 ID입니다.");
    }
}
