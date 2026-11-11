package com.hanjari.common;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** {code,message} 형태로 응답할 업무 오류. 메시지는 고정 문구만 사용하고 사용자 입력을 담지 않는다. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, String> headers;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, String> headers) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
        this.headers = Map.copyOf(headers);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }
}
