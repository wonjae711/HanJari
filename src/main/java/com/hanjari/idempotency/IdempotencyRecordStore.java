package com.hanjari.idempotency;

import java.util.Optional;

/** 멱등 기록 저장소. 모든 메서드는 호출자의 트랜잭션 안에서 실행된다. */
public interface IdempotencyRecordStore {

    /** 범위(회원+operation+키). */
    record Scope(long memberId, IdempotencyOperation operation, IdempotencyKey key) {
    }

    /** 커밋된 기록. 커밋된 기록은 항상 완료 상태다. */
    record StoredRecord(String requestHash, Integer httpStatus, String responseBody) {
        public boolean completed() {
            return httpStatus != null && responseBody != null;
        }
    }

    /**
     * 범위를 선점한다. 고유 제약에 막히면(이미 커밋된 기록이 있음) false. 같은 범위를 선점한 미커밋 트랜잭션이 있으면
     * 그 트랜잭션이 끝날 때까지 DB에서 기다린다: 커밋되면 false, 롤백되면 이 호출이 선점한다(true).
     */
    boolean claim(Scope scope, String requestHash);

    Optional<StoredRecord> find(Scope scope);

    void complete(Scope scope, int httpStatus, String responseBody);
}
