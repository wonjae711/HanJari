package com.hanjari.common;

import java.time.Instant;

/**
 * 변경·조회 판정에 쓰는 기준 시각(HJ-006). 운영 구현은 DB의 {@code UTC_TIMESTAMP(6)}이며, 호출자는 필요한 잠금을 모두 얻은
 * 뒤에 읽어야 한다. 테스트는 경계 시각을 정확히 재현하기 위해 이 빈을 고정 가능한 구현으로 대체한다.
 */
public interface DatabaseTime {

    /** 현재 트랜잭션(있으면)의 연결에서 읽은 UTC 시각. 마이크로초 정밀도. */
    Instant now();
}
