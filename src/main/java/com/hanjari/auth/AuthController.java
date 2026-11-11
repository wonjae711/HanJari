package com.hanjari.auth;

import com.hanjari.common.ApiException;
import com.hanjari.member.Member;
import com.hanjari.member.MemberRepository;
import com.hanjari.member.MemberService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가입·로그인·현재 회원·CSRF 토큰 API. 로그아웃은 Spring Security LogoutFilter(POST /api/v1/auth/logout → 204)가 처리한다.
 * 이 클래스는 로그인 ID·비밀번호·세션 값을 로그에 남기지 않는다.
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    static final String LOGIN_FAILED_MESSAGE = "로그인 ID 또는 비밀번호가 올바르지 않습니다.";

    private final MemberService memberService;
    private final MemberRepository memberRepository;
    private final MemberAuthenticationProvider authenticationProvider;
    private final LoginRateLimiter rateLimiter;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;
    private final Clock clock;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public AuthController(MemberService memberService, MemberRepository memberRepository,
            MemberAuthenticationProvider authenticationProvider, LoginRateLimiter rateLimiter,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            SecurityContextRepository securityContextRepository, Clock clock) {
        this.memberService = memberService;
        this.memberRepository = memberRepository;
        this.authenticationProvider = authenticationProvider;
        this.rateLimiter = rateLimiter;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityContextRepository = securityContextRepository;
        this.clock = clock;
    }

    public record CsrfResponse(String headerName, String token) {
    }

    public record MemberResponse(long memberId, String loginId) {
    }

    /** CSRF 토큰 발급. 토큰은 서버 세션에 저장되므로 세션 쿠키가 함께 발급된다. */
    @GetMapping("/auth/csrf")
    public CsrfResponse csrf(CsrfToken csrfToken) {
        return new CsrfResponse(csrfToken.getHeaderName(), csrfToken.getToken());
    }

    @PostMapping("/auth/signup")
    public ResponseEntity<MemberResponse> signup(@RequestBody(required = false) Map<String, Object> body) {
        MemberService.SignupResult result = memberService.signup(CredentialPolicy.parse(body));
        return ResponseEntity.status(HttpStatus.CREATED).body(new MemberResponse(result.memberId(), result.loginId()));
    }

    @PostMapping("/auth/login")
    public MemberResponse login(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request, HttpServletResponse response) {
        CredentialPolicy.Credentials credentials = CredentialPolicy.parse(body);
        LoginRateLimiter.Decision decision = rateLimiter.tryAcquire(credentials.loginId());
        if (!decision.allowed()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED",
                    "로그인 시도가 너무 많습니다. 잠시 후 다시 시도하세요.",
                    Map.of(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds())));
        }
        Authentication authentication;
        try {
            authentication = authenticationProvider.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(credentials.loginId(), credentials.password()));
        } catch (AuthenticationException failed) {
            // 없는 ID·틀린 비밀번호·형식 위반을 구별하지 않는다.
            throw new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", LOGIN_FAILED_MESSAGE);
        }

        // 세션 고정 방지: 기존 세션 ID와 CSRF 토큰을 교체한 뒤 인증 정보를 저장한다.
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        request.getSession().setAttribute(SessionLifetimeFilter.AUTHENTICATED_AT, clock.millis());

        AuthenticatedMember member = (AuthenticatedMember) authentication.getPrincipal();
        return new MemberResponse(member.memberId(), member.loginId());
    }

    @GetMapping("/me")
    public MemberResponse me(@AuthenticationPrincipal AuthenticatedMember principal, HttpServletRequest request) {
        Optional<Member> member = principal == null ? Optional.empty() : memberRepository.findById(principal.memberId());
        if (member.isEmpty()) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            contextHolder.clearContext();
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요합니다.");
        }
        return new MemberResponse(member.get().id(), member.get().loginId());
    }
}
