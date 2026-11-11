package com.hanjari.idempotency;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 원래 HTTP 상태와 본문 원문. 첫 실행과 재전송이 같은 바이트를 돌려준다. 재전송이면
 * {@value #REPLAYED_HEADER}: true 헤더만 추가한다(상태·본문은 동일).
 */
public record IdempotentResponse(int status, String body, boolean replayed) {

    public static final String REPLAYED_HEADER = "Idempotency-Replayed";

    public ResponseEntity<String> toResponseEntity() {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        if (replayed) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(body);
    }
}
