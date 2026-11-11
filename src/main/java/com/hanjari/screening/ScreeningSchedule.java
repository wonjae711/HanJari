package com.hanjari.screening;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 회차 시간 정책(순수 함수).
 *
 * <ul>
 *   <li>종료 시각 = 시작 + 영화 상영시간(분).</li>
 *   <li>같은 상영관에서 각 회차는 [시작, 종료 + 준비 10분)을 점유한다. 반열린 구간이라 이전 회차 종료 + 10분에
 *       정확히 시작하는 회차는 허용된다.</li>
 *   <li>날짜 조회는 한국 날짜(Asia/Seoul) 기준이다: [해당일 00:00 KST, 다음 날 00:00 KST).</li>
 *   <li>DB에는 UTC LocalDateTime으로 저장한다(드라이버의 시간대 변환을 거치지 않는다).</li>
 * </ul>
 */
public final class ScreeningSchedule {

    public static final Duration PREPARATION = Duration.ofMinutes(10);
    public static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    public static final int RUNTIME_MIN = 1;
    public static final int RUNTIME_MAX = 600;
    public static final long PRICE_MIN = 0;
    public static final long PRICE_MAX = 1_000_000;

    private ScreeningSchedule() {
    }

    public static Instant endsAt(Instant startsAt, int runtimeMinutes) {
        if (runtimeMinutes < RUNTIME_MIN || runtimeMinutes > RUNTIME_MAX) {
            throw new IllegalArgumentException("runtime out of range");
        }
        return startsAt.plus(Duration.ofMinutes(runtimeMinutes));
    }

    /** MySQL DATETIME(6)에 저장·비교할 수 있는 가장 늦은 시각. */
    public static final Instant STORABLE_MAX = Instant.parse("9999-12-31T23:59:59.999999Z");

    /**
     * 시작·종료·점유 종료(종료 + 준비 시간)가 모두 DATETIME(6) 범위 안인지. 점유 종료가 범위를 넘으면 DB의 DATE_ADD 비교가
     * NULL이 되어 중첩 검사가 무력화되므로 함께 확인한다.
     */
    public static boolean fitsStorage(Instant startsAt, Instant endsAt) {
        return !startsAt.isAfter(endsAt) && !occupiedUntil(endsAt).isAfter(STORABLE_MAX);
    }

    /** 상영관 점유 종료(배타) = 종료 + 준비 시간. */
    public static Instant occupiedUntil(Instant endsAt) {
        return endsAt.plus(PREPARATION);
    }

    /** 두 회차의 상영관 점유 구간 [start, end + 10분)이 겹치는지. */
    public static boolean overlaps(Instant startA, Instant endA, Instant startB, Instant endB) {
        return startA.isBefore(occupiedUntil(endB)) && startB.isBefore(occupiedUntil(endA));
    }

    /** 한국 날짜 하루의 UTC 구간 [from, to). */
    public record UtcRange(Instant from, Instant to) {
    }

    public static UtcRange koreanDay(LocalDate date) {
        return new UtcRange(date.atStartOfDay(KOREA).toInstant(), date.plusDays(1).atStartOfDay(KOREA).toInstant());
    }

    public static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static Instant fromDb(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
