package com.hanjari.idempotency;

/**
 * 멱등 기록에 접근하기 전에 실행하는 접근 검사(HJ-005 AC5).
 *
 * <p>{@link IdempotencyExecutor}는 이 검사를 실행 트랜잭션 안에서 범위 선점·기록 조회·재전송·충돌 판정보다 먼저 실행한다.
 * 경로 대상 리소스가 없거나 요청 회원 소유가 아니면 404 {@code ApiException}을 던져야 한다. 그러면 기존 기록의 존재·응답·
 * 충돌 여부가 드러나지 않고 선점 행도 남지 않는다. 검사는 불변 소유자 정보만 읽고 잠금을 잡지 않는다(잠금과 상태 재검증은
 * 도메인 작업이 승인된 순서로 다시 수행한다).
 */
@FunctionalInterface
public interface IdempotencyAccessCheck {

    /**
     * 경로에 기존 소유 리소스가 없는 작업(확보 생성)용. 소유 리소스 대상 작업에 넘기면 실행기가 거부한다.
     */
    IdempotencyAccessCheck NO_TARGET_RESOURCE = () -> { };

    /** 접근 불가면 기록·결과를 담지 않은 404 등 {@code ApiException}을 던진다. */
    void verify();
}
