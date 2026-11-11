package com.hanjari.hold;

import com.hanjari.common.TransientDataAccessFailures;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료 확보 정리 작업자(HJ-006). 기본 1초 주기로 만료 시각이 지난 ACTIVE 확보를 찾아 하나씩 독립 트랜잭션으로 만료 처리한다.
 *
 * <p>정합성은 이 작업자에 의존하지 않는다. 확보·해제·조회가 만료를 다시 판정하므로 작업자가 멈춰도 만료 좌석은 다시 확보할 수 있다.
 * 후보 목록은 잠금 없이 읽은 스냅샷이라 오래될 수 있다. 각 처리는 {@link HoldService#expireIfDue(long)}가 잠금 아래 재검증하고
 * 대상 확보가 현재 소유한 좌석만 비우므로, 늦게 실행되거나 다시 실행돼도 새 소유자에게 영향이 없다.
 *
 * <p>{@code hanjari.holds.expiry-worker.enabled}로 켜고 끈다(테스트는 기본으로 끈다). 로그에는 고정 이벤트 코드와 건수만 남긴다.
 */
@Component
public class HoldExpiryWorker {

    static final int BATCH_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryWorker.class);

    private final HoldService holdService;
    private final AtomicBoolean enabled;

    public HoldExpiryWorker(HoldService holdService,
            @Value("${hanjari.holds.expiry-worker.enabled:true}") boolean enabled) {
        this.holdService = holdService;
        this.enabled = new AtomicBoolean(enabled);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean value) {
        enabled.set(value);
    }

    @Scheduled(fixedDelayString = "${hanjari.holds.expiry-worker.interval-ms:1000}",
            initialDelayString = "${hanjari.holds.expiry-worker.interval-ms:1000}")
    public void scheduledSweep() {
        if (!enabled.get()) {
            return;
        }
        try {
            sweepOnce();
        } catch (RuntimeException failure) {
            // 다음 주기에 다시 시도한다. 예외 객체·SQL은 기록하지 않는다.
            log.warn("event=HOLD_EXPIRY_SWEEP_FAILED category={}",
                    TransientDataAccessFailures.isTransient(failure) ? "DB_TRANSIENT" : "UNEXPECTED");
        }
    }

    /** 한 번 정리한다. 반환값은 이번에 만료를 반영한 건수다. 한 건의 실패가 나머지 처리를 막지 않는다. */
    public int sweepOnce() {
        return process(holdService.dueHoldIds(BATCH_SIZE));
    }

    /** 주어진 후보(오래된 스냅샷일 수 있음)를 처리한다. */
    public int process(List<Long> candidates) {
        int expired = 0;
        int failed = 0;
        for (long holdId : candidates) {
            try {
                if (holdService.expireIfDue(holdId)) {
                    expired++;
                }
            } catch (RuntimeException failure) {
                failed++;
            }
        }
        if (expired > 0 || failed > 0) {
            log.info("event=HOLD_EXPIRY_SWEEP expired={} failed={}", expired, failed);
        }
        return expired;
    }
}
