package com.hanjari.idempotency;

import java.util.Map;
import java.util.Objects;

/**
 * 멱등 작업이 돌려주는 종결 결과. 2xx만 허용한다(오류는 {@link com.hanjari.common.ApiException}을 던져 롤백한다).
 * 본문은 {@link CanonicalJson}이 직렬화할 수 있는 JSON 객체이며, 직렬화한 원문이 그대로 저장·재전송된다.
 * 응답 본문에 비밀번호·세션·토큰 같은 인증 비밀을 넣지 않는다.
 */
public record IdempotentResult(int status, Map<String, ?> body) {

    public IdempotentResult {
        if (status < 200 || status > 299) {
            throw new IllegalArgumentException("only 2xx results can be recorded");
        }
        Objects.requireNonNull(body, "body");
    }

    public static IdempotentResult ok(Map<String, ?> body) {
        return new IdempotentResult(200, body);
    }

    public static IdempotentResult created(Map<String, ?> body) {
        return new IdempotentResult(201, body);
    }

    /** 비동기 접수. 본문에는 작업 ID·조회 URL 같은 접수 시점 정보만 담고 최신 상태는 조회 API로 확인한다. */
    public static IdempotentResult accepted(Map<String, ?> body) {
        return new IdempotentResult(202, body);
    }
}
