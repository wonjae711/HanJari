package com.hanjari.hold;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 확보 시간·수량·금액 정책(순수 함수, HJ-006).
 *
 * <ul>
 *   <li>한 확보는 한 회차의 서로 다른 1~4석이다.</li>
 *   <li>만료 시각 = min(생성 시각 + 5분, 회차 시작). 조회·재전송·새로고침으로 연장하지 않는다.</li>
 *   <li>{@code now >= expiresAt}이면 만료다(경계 시각 자체가 만료). {@code now >= startsAt}이면 새 확보를 금지한다.</li>
 *   <li>총액 = 회차 기본 가격 × 좌석 수. 서버가 계산하고 확보 시 저장한다.</li>
 * </ul>
 */
public final class HoldPolicy {

    public static final int MIN_SEATS = 1;
    public static final int MAX_SEATS = 4;
    public static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    private HoldPolicy() {
    }

    public static Instant expiresAt(Instant createdAt, Instant screeningStartsAt) {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(screeningStartsAt, "screeningStartsAt");
        Instant byDuration = createdAt.plus(HOLD_DURATION);
        return byDuration.isBefore(screeningStartsAt) ? byDuration : screeningStartsAt;
    }

    public static boolean isExpired(Instant now, Instant expiresAt) {
        return !now.isBefore(expiresAt);
    }

    public static boolean hasStarted(Instant now, Instant screeningStartsAt) {
        return !now.isBefore(screeningStartsAt);
    }

    public static boolean validSeatCount(int seats) {
        return seats >= MIN_SEATS && seats <= MAX_SEATS;
    }

    public static long totalAmount(long unitPrice, int seats) {
        if (unitPrice < 0 || !validSeatCount(seats)) {
            throw new IllegalArgumentException("invalid price or seat count");
        }
        return Math.multiplyExact(unitPrice, (long) seats);
    }

    /** 저장 상태와 기준 시각으로 본 실제 상태. 만료가 지났지만 아직 반영되지 않은 ACTIVE는 EXPIRED로 본다. */
    public static HoldStatus effectiveStatus(HoldStatus stored, Instant now, Instant expiresAt) {
        if (stored == HoldStatus.ACTIVE && isExpired(now, expiresAt)) {
            return HoldStatus.EXPIRED;
        }
        return stored;
    }
}
