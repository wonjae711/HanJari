package com.hanjari.auth;

import com.hanjari.common.TimeConfig;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/**
 * HJ-003 인증·권한 정책.
 *
 * <ul>
 *   <li>서버 세션(Spring Session JDBC). 로그인 성공 시 세션 ID와 CSRF 토큰을 교체한다.</li>
 *   <li>모든 상태 변경 요청(가입·로그인·로그아웃 포함)에 로컬 Origin 검사 후 CSRF 검사를 적용한다.</li>
 *   <li>미인증 401 UNAUTHENTICATED, 권한 부족 403 FORBIDDEN, CSRF 실패 403 CSRF_INVALID.</li>
 *   <li>운영 API(/api/v1/ops/**)는 OPERATOR만. 공개 가입은 항상 MEMBER다.</li>
 *   <li>httpBasic·formLogin·요청 캐시를 쓰지 않는다.</li>
 * </ul>
 *
 * <p>{@code HanjariApplication}이 이 설정을 import하므로 WebMvc 슬라이스 테스트에도 같은 정책이 적용된다.
 */
@Configuration
@EnableWebSecurity
@Import(TimeConfig.class)
public class SecurityConfig {

    public static final String LOGOUT_PATH = "/api/v1/auth/logout";

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
    }

    /** 로그인 성공 시: 세션 ID 교체 → CSRF 토큰 교체. */
    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokenRepository) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokenRepository)));
    }

    /** Spring Session 필터(order = MIN_VALUE + 50)보다 바깥에서 인증 경로의 필터 단계 장애를 정책 응답으로 바꾼다. */
    @Bean
    public FilterRegistrationBean<AuthFailureGuardFilter> authFailureGuardFilter() {
        FilterRegistrationBean<AuthFailureGuardFilter> registration =
                new FilterRegistrationBean<>(new AuthFailureGuardFilter());
        registration.setOrder(AUTH_FAILURE_GUARD_ORDER);
        registration.addUrlPatterns("/api/v1/*");
        return registration;
    }

    static final int AUTH_FAILURE_GUARD_ORDER = Integer.MIN_VALUE + 40;

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, CsrfTokenRepository csrfTokenRepository,
            SecurityContextRepository securityContextRepository, Clock clock,
            @Value("${hanjari.security.allowed-origins:http://localhost:8080}") List<String> allowedOrigins)
            throws Exception {
        http
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .requestCache(cache -> cache.disable())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .logout(logout -> logout
                        .logoutUrl(LOGOUT_PATH)
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ex) -> SecurityErrorWriter.write(response,
                                HttpServletResponse.SC_UNAUTHORIZED, SecurityErrorWriter.UNAUTHENTICATED,
                                "로그인이 필요합니다."))
                        .accessDeniedHandler((request, response, ex) -> {
                            if (ex instanceof CsrfException) {
                                SecurityErrorWriter.write(response, HttpServletResponse.SC_FORBIDDEN,
                                        SecurityErrorWriter.CSRF_INVALID, "CSRF 토큰이 없거나 올바르지 않습니다.");
                            } else {
                                SecurityErrorWriter.write(response, HttpServletResponse.SC_FORBIDDEN,
                                        SecurityErrorWriter.FORBIDDEN, "권한이 없습니다.");
                            }
                        }))
                .addFilterAfter(new SessionLifetimeFilter(clock), SecurityContextHolderFilter.class)
                .addFilterBefore(new LocalOriginFilter(allowedOrigins), CsrfFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/movies/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/signup", "/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/ops/**").hasRole("OPERATOR")
                        .anyRequest().authenticated());
        return http.build();
    }
}
