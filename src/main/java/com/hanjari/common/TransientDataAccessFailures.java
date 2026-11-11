package com.hanjari.common;

import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * 일시적 DB 장애(연결 실패·잠금 대기 초과·데드락·쿼리 시간 초과 등) 판별.
 *
 * <p>같은 요청을 다시 보내면 성공할 수 있는 장애만 true다. 제약 위반(중복 키 등)·SQL 문법 오류처럼 재시도해도
 * 결과가 같은 장애는 false다. 원인 사슬을 따라가며 판정하고 순환 참조는 깊이 제한으로 끊는다.
 */
public final class TransientDataAccessFailures {

    private static final int MAX_DEPTH = 16;

    private TransientDataAccessFailures() {
    }

    public static boolean isTransient(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            if (current instanceof TransientDataAccessException          // 잠금 실패·데드락·쿼리 시간 초과
                    || current instanceof RecoverableDataAccessException
                    || current instanceof DataAccessResourceFailureException // 연결 획득 실패 포함
                    || current instanceof CannotCreateTransactionException
                    || current instanceof SQLTransientException
                    || current instanceof SQLRecoverableException) {
                return true;
            }
            if (current.getCause() == current) {
                return false;
            }
            current = current.getCause();
        }
        return false;
    }
}
