package com.hanjari.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 상태 변경 요청(POST·PUT·PATCH·DELETE)은 Origin 헤더가 설정된 로컬 origin 중 하나와 정확히 같아야 한다.
 * 누락·"null"·불일치는 403 ORIGIN_NOT_ALLOWED. CSRF 검사보다 먼저 실행된다.
 */
public class LocalOriginFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final Set<String> allowedOrigins;

    public LocalOriginFilter(List<String> allowedOrigins) {
        this.allowedOrigins = Set.copyOf(allowedOrigins);
    }

    public boolean isAllowed(String method, String origin) {
        if (SAFE_METHODS.contains(method)) {
            return true;
        }
        return origin != null && allowedOrigins.contains(origin);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isAllowed(request.getMethod(), request.getHeader("Origin"))) {
            SecurityErrorWriter.write(response, HttpServletResponse.SC_FORBIDDEN,
                    SecurityErrorWriter.ORIGIN_NOT_ALLOWED, "허용되지 않은 출처의 요청입니다.");
            return;
        }
        chain.doFilter(request, response);
    }
}
