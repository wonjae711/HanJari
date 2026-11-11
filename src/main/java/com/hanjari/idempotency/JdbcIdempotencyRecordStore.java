package com.hanjari.idempotency;

import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** MySQL idempotency_records 구현. 고유 제약 uk_idempotency_scope가 동시 선점의 최종 판정자다. */
@Repository
public class JdbcIdempotencyRecordStore implements IdempotencyRecordStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcIdempotencyRecordStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean claim(Scope scope, String requestHash) {
        try {
            jdbcTemplate.update("INSERT INTO idempotency_records "
                            + "(member_id, operation, idempotency_key, request_hash, created_at) "
                            + "VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6))",
                    scope.memberId(), scope.operation().name(), scope.key().value(), requestHash);
            return true;
        } catch (DuplicateKeyException alreadyRecorded) {
            return false;
        }
    }

    @Override
    public Optional<StoredRecord> find(Scope scope) {
        // READ COMMITTED 일반 읽기: 경쟁 트랜잭션이 커밋한 최신 기록을 본다.
        List<StoredRecord> rows = jdbcTemplate.query(
                "SELECT request_hash, http_status, response_body FROM idempotency_records "
                        + "WHERE member_id = ? AND operation = ? AND idempotency_key = ?",
                (rs, n) -> new StoredRecord(rs.getString("request_hash"),
                        (Integer) rs.getObject("http_status", Integer.class), rs.getString("response_body")),
                scope.memberId(), scope.operation().name(), scope.key().value());
        return rows.stream().findFirst();
    }

    @Override
    public void complete(Scope scope, int httpStatus, String responseBody) {
        int updated = jdbcTemplate.update("UPDATE idempotency_records "
                        + "SET http_status = ?, response_body = ?, completed_at = UTC_TIMESTAMP(6) "
                        + "WHERE member_id = ? AND operation = ? AND idempotency_key = ? AND http_status IS NULL",
                httpStatus, responseBody, scope.memberId(), scope.operation().name(), scope.key().value());
        if (updated != 1) {
            throw new IllegalStateException("idempotency record was not claimed by this transaction");
        }
    }
}
