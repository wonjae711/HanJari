package com.hanjari.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;

/** 보안 필터 단계의 {code,message} 응답. 고정 문구만 쓰며 요청 내용·예외 메시지를 포함하지 않는다. */
final class SecurityErrorWriter {

    static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    static final String FORBIDDEN = "FORBIDDEN";
    static final String CSRF_INVALID = "CSRF_INVALID";
    static final String ORIGIN_NOT_ALLOWED = "ORIGIN_NOT_ALLOWED";

    private SecurityErrorWriter() {
    }

    static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // code·message는 이 패키지의 상수뿐이라 JSON 이스케이프가 필요한 문자가 없다.
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
        response.getWriter().flush();
    }
}
