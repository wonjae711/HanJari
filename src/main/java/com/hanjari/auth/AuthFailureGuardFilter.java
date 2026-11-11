package com.hanjari.auth;

import com.hanjari.common.AuthFailurePolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 인증 경로에서 MVC 처리기 밖(세션 저장소 조회·커밋, 보안 필터)에서 발생한 장애를 정책 응답으로 바꾼다.
 *
 * <p>Spring Session 필터보다 바깥에서 동작해 세션 테이블 접근 실패도 잡는다. 예외를 다시 던지면 서블릿 컨테이너가
 * 예외 원문·스택을 로그에 남기므로 다시 던지지 않는다. 응답이 이미 커밋된 경우에는 고정 이벤트 로그만 남긴다.
 * 인증 경로가 아닌 요청은 그대로 통과시킨다(기존 영화 API 계약 유지).
 */
public class AuthFailureGuardFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !AuthFailurePolicy.isAuthPath(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException failure) {
            AuthFailurePolicy.Outcome outcome = AuthFailurePolicy.classify(failure);
            AuthFailurePolicy.record(outcome, "FILTER");
            if (!response.isCommitted()) {
                response.reset();
                SecurityErrorWriter.write(response, outcome.status().value(), outcome.code(), outcome.message());
            }
        }
    }
}
