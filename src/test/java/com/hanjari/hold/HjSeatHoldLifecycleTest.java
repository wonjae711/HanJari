package com.hanjari.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hanjari.common.ApiException;
import com.hanjari.idempotency.IdempotentRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;

/**
 * HJ-006 확보 정책의 DB 없는 단위 검증: 만료 시각 = min(생성+5분, 시작), 경계(now == expiresAt이면 만료, now == startsAt이면
 * 신규 금지), 종결 상태 불변, 서버 총액, 1~4석·중복·형식 입력 규칙, 만료 작업자의 켜기/끄기·실패 격리.
 * 잠금·동시성·DB 판정은 {@code HjSeatHoldConcurrencyIT}가 실제 MySQL로 검증한다. 시각은 고정 값으로 제어한다(sleep 없음).
 */
class HjSeatHoldLifecycleTest {

    private static final Instant T0 = Instant.parse("2031-05-01T03:00:00Z");
    private static final Duration MICRO = Duration.ofNanos(1_000);

    // AC3 만료 시각과 경계 ---------------------------------------------------------------------------------

    @Test
    void expiryIsFiveMinutesAfterCreationWhenTheScreeningStartsLater() {
        assertThat(HoldPolicy.HOLD_DURATION).isEqualTo(Duration.ofMinutes(5));
        assertThat(HoldPolicy.expiresAt(T0, T0.plus(Duration.ofHours(2)))).isEqualTo(T0.plus(Duration.ofMinutes(5)));
        assertThat(HoldPolicy.expiresAt(T0, T0.plus(Duration.ofMinutes(5)).plus(MICRO)))
                .isEqualTo(T0.plus(Duration.ofMinutes(5)));
        assertThatThrownBy(() -> HoldPolicy.expiresAt(null, T0)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void expiryIsTheScreeningStartWhenItComesFirstOrAtTheSameInstant() {
        Instant soon = T0.plus(Duration.ofMinutes(2));
        assertThat(HoldPolicy.expiresAt(T0, soon)).isEqualTo(soon);
        assertThat(HoldPolicy.expiresAt(T0, T0.plus(Duration.ofMinutes(5)))).isEqualTo(T0.plus(Duration.ofMinutes(5)));
        assertThat(HoldPolicy.expiresAt(T0, T0.plus(MICRO))).isEqualTo(T0.plus(MICRO));
    }

    @Test
    void holdIsExpiredExactlyAtExpiresAtAndNotOneMicrosecondBefore() {
        Instant expiresAt = HoldPolicy.expiresAt(T0, T0.plus(Duration.ofHours(1)));
        assertThat(HoldPolicy.isExpired(expiresAt.minus(MICRO), expiresAt)).isFalse();
        assertThat(HoldPolicy.isExpired(expiresAt, expiresAt)).isTrue();
        assertThat(HoldPolicy.isExpired(expiresAt.plus(MICRO), expiresAt)).isTrue();
        assertThat(HoldPolicy.effectiveStatus(HoldStatus.ACTIVE, expiresAt.minus(MICRO), expiresAt))
                .isEqualTo(HoldStatus.ACTIVE);
        assertThat(HoldPolicy.effectiveStatus(HoldStatus.ACTIVE, expiresAt, expiresAt)).isEqualTo(HoldStatus.EXPIRED);
    }

    @Test
    void newHoldsAreForbiddenFromTheScreeningStartInstant() {
        Instant startsAt = T0.plus(Duration.ofMinutes(30));
        assertThat(HoldPolicy.hasStarted(startsAt.minus(MICRO), startsAt)).isFalse();
        assertThat(HoldPolicy.hasStarted(startsAt, startsAt)).isTrue();
        assertThat(HoldPolicy.hasStarted(startsAt.plusSeconds(1), startsAt)).isTrue();
        // 시작 1µs 전에 만든 확보는 시작 시각에 만료된다.
        Instant lastMoment = startsAt.minus(MICRO);
        assertThat(HoldPolicy.expiresAt(lastMoment, startsAt)).isEqualTo(startsAt);
        assertThat(HoldPolicy.isExpired(startsAt, HoldPolicy.expiresAt(lastMoment, startsAt))).isTrue();
    }

    @Test
    void terminalStatusesNeverReviveOrChangeWithTime() {
        Instant expiresAt = T0.plus(Duration.ofMinutes(5));
        for (HoldStatus terminal : List.of(HoldStatus.RELEASED, HoldStatus.EXPIRED, HoldStatus.CONSUMED)) {
            assertThat(terminal.terminal()).isTrue();
            assertThat(HoldPolicy.effectiveStatus(terminal, T0, expiresAt)).isEqualTo(terminal);
            assertThat(HoldPolicy.effectiveStatus(terminal, expiresAt.plusSeconds(60), expiresAt)).isEqualTo(terminal);
        }
        assertThat(HoldStatus.ACTIVE.terminal()).isFalse();
        assertThat(HoldStatus.values()).extracting(Enum::name)
                .containsExactly("ACTIVE", "RELEASED", "EXPIRED", "CONSUMED");
    }

    // AC1 수량·금액·입력 ---------------------------------------------------------------------------------

    @Test
    void totalAmountIsUnitPriceTimesSeatCountWithinOneToFourSeats() {
        assertThat(HoldPolicy.totalAmount(12_000, 1)).isEqualTo(12_000);
        assertThat(HoldPolicy.totalAmount(12_000, 4)).isEqualTo(48_000);
        assertThat(HoldPolicy.totalAmount(0, 3)).isZero();
        assertThat(HoldPolicy.totalAmount(1_000_000, 4)).isEqualTo(4_000_000);
        for (int seats : new int[] {0, 5, -1}) {
            assertThat(HoldPolicy.validSeatCount(seats)).isFalse();
            assertThatThrownBy(() -> HoldPolicy.totalAmount(1000, seats)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> HoldPolicy.totalAmount(-1, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void holdRequestAcceptsOneAndFourDistinctSeatsAndFingerprintIgnoresOrder() {
        HoldRequestPolicy.HoldCreate one = HoldRequestPolicy.holdCreate(body(7, List.of(30)));
        assertThat(one.screeningId()).isEqualTo(7);
        assertThat(one.seatIds()).containsExactly(30L);

        HoldRequestPolicy.HoldCreate four = HoldRequestPolicy.holdCreate(body(7, List.of(40, 10, 30, 20)));
        assertThat(four.seatIds()).containsExactly(40L, 10L, 30L, 20L);
        assertThat(four.sortedSeatIds()).containsExactly(10L, 20L, 30L, 40L);
        IdempotentRequest permuted = HoldRequestPolicy.holdCreate(body(7, List.of(10, 20, 30, 40))).fingerprint();
        assertThat(four.fingerprint().hash()).isEqualTo(permuted.hash())
                .isEqualTo(IdempotentRequest.holdCreate(7, List.of(20L, 40L, 10L, 30L)).hash());
        assertThat(HoldRequestPolicy.holdCreate(body(8, List.of(10, 20, 30, 40))).fingerprint().hash())
                .isNotEqualTo(permuted.hash());
    }

    @Test
    void holdRequestRejectsZeroAndFiveOrMoreSeats() {
        for (List<Integer> seats : List.of(List.<Integer>of(), List.of(1, 2, 3, 4, 5), List.of(1, 2, 3, 4, 5, 6))) {
            ApiException rejected = catchThrowableOfType(ApiException.class,
                    () -> HoldRequestPolicy.holdCreate(body(7, seats)));
            assertThat(rejected).as("%s", seats).isNotNull();
            assertThat(rejected.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(rejected.getCode()).isEqualTo(HoldRequestPolicy.INVALID_SEAT_COUNT);
        }
    }

    @Test
    void holdRequestRejectsDuplicateSeatIdsInsteadOfNormalizingThem() {
        for (List<Integer> seats : List.of(List.of(5, 5), List.of(1, 2, 1), List.of(9, 8, 7, 9))) {
            ApiException rejected = catchThrowableOfType(ApiException.class,
                    () -> HoldRequestPolicy.holdCreate(body(7, seats)));
            assertThat(rejected).as("%s", seats).isNotNull();
            assertThat(rejected.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(rejected.getCode()).isEqualTo(IdempotentRequest.DUPLICATE_SEAT_CODE);
        }
    }

    @Test
    void holdRequestRejectsMalformedBodiesWithFixedCodes() {
        Map<String, Object> extra = body(7, List.of(1));
        extra.put("totalAmount", 1);
        Map<String, Object> missingSeats = new HashMap<>();
        missingSeats.put("screeningId", 7);
        Map<String, Object> missingScreening = new HashMap<>();
        missingScreening.put("seatIds", List.of(1));
        List<Object> withNull = new ArrayList<>();
        withNull.add(null);
        Map<Map<String, Object>, String> cases = new java.util.LinkedHashMap<>();
        cases.put(extra, "UNSUPPORTED_FIELD");
        cases.put(missingSeats, HoldRequestPolicy.INVALID_SEAT_IDS);
        cases.put(missingScreening, "INVALID_SCREENING_ID");
        cases.put(body("7", List.of(1)), "INVALID_SCREENING_ID");
        cases.put(body(0, List.of(1)), "INVALID_SCREENING_ID");
        cases.put(body(7.0, List.of(1)), "INVALID_SCREENING_ID");
        cases.put(body(7, List.of(1.5)), HoldRequestPolicy.INVALID_SEAT_IDS);
        cases.put(body(7, List.of("1")), HoldRequestPolicy.INVALID_SEAT_IDS);
        cases.put(body(7, List.of(-1)), HoldRequestPolicy.INVALID_SEAT_IDS);
        cases.put(body(7, withNull), HoldRequestPolicy.INVALID_SEAT_IDS);
        cases.put(body(7, "1,2"), HoldRequestPolicy.INVALID_SEAT_IDS);
        cases.forEach((request, code) -> {
            ApiException rejected = catchThrowableOfType(ApiException.class, () -> HoldRequestPolicy.holdCreate(request));
            assertThat(rejected).as("%s", request).isNotNull();
            assertThat(rejected.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(rejected.getCode()).as("%s", request).isEqualTo(code);
            assertThat(rejected.getMessage()).doesNotContain("1.5").doesNotContain("1,2");
        });
        assertThat(catchThrowableOfType(ApiException.class, () -> HoldRequestPolicy.holdCreate(null)).getCode())
                .isEqualTo("INVALID_REQUEST");
    }

    // AC4 만료 작업자 -------------------------------------------------------------------------------------

    @Test
    void disabledWorkerTouchesNothingAndEnabledWorkerExpiresEachDueCandidate() {
        HoldService service = mock(HoldService.class);
        HoldExpiryWorker worker = new HoldExpiryWorker(service, false);
        worker.scheduledSweep();
        verify(service, never()).dueHoldIds(anyInt());
        verify(service, never()).expireIfDue(anyLong());

        when(service.dueHoldIds(HoldExpiryWorker.BATCH_SIZE)).thenReturn(List.of(11L, 12L, 13L));
        when(service.expireIfDue(11L)).thenReturn(true);
        when(service.expireIfDue(12L)).thenReturn(false); // 이미 종결·재확보된 오래된 후보: 변화 없음
        when(service.expireIfDue(13L)).thenReturn(true);
        worker.setEnabled(true);
        assertThat(worker.isEnabled()).isTrue();
        worker.scheduledSweep();
        verify(service).expireIfDue(11L);
        verify(service).expireIfDue(12L);
        verify(service).expireIfDue(13L);
        assertThat(worker.sweepOnce()).isEqualTo(2);
    }

    @Test
    void oneFailingCandidateDoesNotStopTheRestAndSweepFailuresDoNotEscapeTheScheduler() {
        HoldService service = mock(HoldService.class);
        HoldExpiryWorker worker = new HoldExpiryWorker(service, true);
        when(service.expireIfDue(21L)).thenThrow(new CannotAcquireLockException("lock wait timeout"));
        when(service.expireIfDue(22L)).thenReturn(true);
        assertThat(worker.process(List.of(21L, 22L))).isEqualTo(1);
        verify(service).expireIfDue(22L);

        when(service.dueHoldIds(anyInt())).thenThrow(new CannotAcquireLockException("db down"));
        worker.scheduledSweep(); // 예외를 던지지 않고 다음 주기를 기다린다.
        assertThatThrownBy(worker::sweepOnce).isInstanceOf(CannotAcquireLockException.class);
    }

    private static Map<String, Object> body(Object screeningId, Object seatIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("screeningId", screeningId);
        body.put("seatIds", seatIds);
        return body;
    }
}
