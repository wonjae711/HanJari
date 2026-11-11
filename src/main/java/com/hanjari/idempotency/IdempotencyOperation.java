package com.hanjari.idempotency;

/**
 * 멱등 키를 요구하는 변경 작업. 기록 범위는 회원 + operation + 키다.
 *
 * <p>가입·로그인·로그아웃은 대상이 아니다(비밀번호·세션 요청을 기록하지 않는다). 값을 추가하면
 * {@code V4__create_idempotency_records.sql}의 CHECK도 새 마이그레이션으로 함께 바꿔야 한다.
 *
 * <p>{@link #targetsOwnedResource()}가 참인 작업은 경로의 기존 회원 소유 리소스(확보·예매)를 대상으로 한다.
 * 이 작업들은 멱등 기록을 조회·재전송·충돌 판정하기 전에 소유권 검사({@link IdempotencyAccessCheck})를 반드시 거친다.
 */
public enum IdempotencyOperation {
    HOLD_CREATE(false),
    HOLD_RELEASE(true),
    PAYMENT_ATTEMPT_CREATE(true),
    RESERVATION_CANCEL(true);

    private final boolean targetsOwnedResource;

    IdempotencyOperation(boolean targetsOwnedResource) {
        this.targetsOwnedResource = targetsOwnedResource;
    }

    /** 경로의 기존 회원 소유 리소스를 대상으로 하는 작업인지(→ 기록 접근 전 소유권 검사 필수). */
    public boolean targetsOwnedResource() {
        return targetsOwnedResource;
    }
}
