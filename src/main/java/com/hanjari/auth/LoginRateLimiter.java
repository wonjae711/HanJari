package com.hanjari.auth;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 로그인 시도 제한(인스턴스 메모리). 분 단위 고정 창(UTC epoch 분)마다
 * 로그인 ID별 {@value #PER_LOGIN_ID_PER_MINUTE}회, 인스턴스 전체 {@value #GLOBAL_PER_MINUTE}회까지 허용한다.
 *
 * <p>성공·실패와 무관하게 허용된 시도를 센다. 거부된 시도는 세지 않는다. IP·User-Agent는 저장하지 않는다.
 * 형식에 맞지 않는 로그인 ID는 원문을 키로 쓰지 않고 하나의 공용 키로 센다. 창이 바뀌면 모든 기록을 버린다.
 */
@Component
public class LoginRateLimiter {

    public static final int PER_LOGIN_ID_PER_MINUTE = 5;
    public static final int GLOBAL_PER_MINUTE = 100;
    private static final long WINDOW_MILLIS = 60_000L;
    private static final String MALFORMED_KEY = "\u0000malformed";

    private final Clock clock;
    private long currentWindow = Long.MIN_VALUE;
    private int globalCount;
    private final Map<String, Integer> perLoginId = new HashMap<>();

    public LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** @param retryAfterSeconds 거부 시 다음 창까지 남은 초(1 이상). 허용이면 0. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    public synchronized Decision tryAcquire(String loginId) {
        long now = clock.millis();
        long window = Math.floorDiv(now, WINDOW_MILLIS);
        if (window != currentWindow) {
            currentWindow = window;
            globalCount = 0;
            perLoginId.clear();
        }
        String key = CredentialPolicy.isValidLoginId(loginId) ? loginId : MALFORMED_KEY;
        int used = perLoginId.getOrDefault(key, 0);
        if (globalCount >= GLOBAL_PER_MINUTE || used >= PER_LOGIN_ID_PER_MINUTE) {
            long untilNextWindow = (window + 1) * WINDOW_MILLIS - now;
            return new Decision(false, Math.max(1L, (untilNextWindow + 999L) / 1000L));
        }
        globalCount++;
        perLoginId.put(key, used + 1);
        return new Decision(true, 0L);
    }
}
