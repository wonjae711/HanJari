package com.hanjari.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 절대 만료(로그인 시각 + 12시간). 유휴 만료 30분은 Spring Session(MAX_INACTIVE_INTERVAL)이 처리한다.
 *
 * <p>로그인 시각이 지나 {@code now >= authenticatedAt + 12h}이면 서버 세션을 폐기하고(JDBC 행 삭제)
 * 인증 정보를 비운다. 이후 보호된 요청은 401이 된다. 인증 정보가 있는데 로그인 시각이 없는 세션도 폐기한다.
 */
public class SessionLifetimeFilter extends OncePerRequestFilter {

    public static final String AUTHENTICATED_AT = "hanjari.authenticatedAtEpochMillis";
    public static final Duration ABSOLUTE_TIMEOUT = Duration.ofHours(12);

    private final Clock clock;

    public SessionLifetimeFilter(Clock clock) {
        this.clock = clock;
    }

    public static boolean isAbsolutelyExpired(long authenticatedAtMillis, long nowMillis) {
        return nowMillis - authenticatedAtMillis >= ABSOLUTE_TIMEOUT.toMillis();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object authenticatedAt = session.getAttribute(AUTHENTICATED_AT);
            boolean hasSecurityContext =
                    session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) != null;
            boolean expired = authenticatedAt instanceof Long at
                    ? isAbsolutelyExpired(at, clock.millis())
                    : hasSecurityContext;
            if (expired) {
                session.invalidate();
                SecurityContextHolder.getContextHolderStrategy().clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
