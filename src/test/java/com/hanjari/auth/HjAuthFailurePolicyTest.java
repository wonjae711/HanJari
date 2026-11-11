package com.hanjari.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hanjari.common.ApiExceptionHandler;
import com.hanjari.common.AuthFailurePolicy;
import com.hanjari.common.TransientDataAccessFailures;
import com.hanjari.member.MemberRepository;
import com.hanjari.member.MemberRole;
import com.hanjari.member.MemberService;
import jakarta.servlet.ServletException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HJ-003 인증 경로의 DB 장애 응답(일시 장애 503)과 장애 로그 허용 목록을 장애 주입으로 검증한다.
 * 주입하는 예외 메시지·원인에는 민감 문자열을 넣고, 응답·로그 어디에도 나오지 않는지 확인한다.
 */
class HjAuthFailurePolicyTest {

    private static final String SECRET_PASSWORD = "hunter2-secret-pass";
    private static final String SECRET_LOGIN = "leaky_login_id";
    private static final String SECRET_SQL = "INSERT INTO members (login_id, password_hash) VALUES ('" + SECRET_LOGIN
            + "', '$argon2id$v=19$m=16384,t=2,p=1$c2FsdA$aGFzaA')";
    private static final String SECRET_URL = "jdbc:mysql://secret-host:3306/hanjari_test?password=" + SECRET_PASSWORD;
    private static final List<String> SENSITIVE = List.of(SECRET_PASSWORD, SECRET_LOGIN, "secret-host", "jdbc:",
            "$argon2id$", "INSERT INTO", "Exception", "password=");

    private MemberRepository repository;
    private MemberAuthenticationProvider authenticationProvider;
    private MockMvc mockMvc;
    private ListAppender<ILoggingEvent> appender;
    private final List<Logger> observed = List.of(
            (Logger) LoggerFactory.getLogger(AuthFailurePolicy.class),
            (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class));

    @BeforeEach
    void setUp() {
        repository = mock(MemberRepository.class);
        authenticationProvider = mock(MemberAuthenticationProvider.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        given(encoder.encode(any())).willReturn("$argon2id$v=19$m=16384,t=2,p=1$c2FsdA$aGFzaA");
        AuthController controller = new AuthController(new MemberService(repository, encoder), repository,
                authenticationProvider, new LoginRateLimiter(Clock.systemUTC()),
                mock(SessionAuthenticationStrategy.class), mock(SecurityContextRepository.class), Clock.systemUTC());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        appender = new ListAppender<>();
        appender.start();
        observed.forEach(logger -> logger.addAppender(appender));
    }

    @AfterEach
    void tearDown() {
        observed.forEach(logger -> logger.detachAppender(appender));
        SecurityContextHolder.clearContext();
    }

    @Test
    void transientDatabaseFailuresAreClassifiedThroughCauseChainButIntegrityAndGrammarErrorsAreNot() {
        assertThat(TransientDataAccessFailures.isTransient(new CannotGetJdbcConnectionException(SECRET_URL))).isTrue();
        assertThat(TransientDataAccessFailures.isTransient(new CannotAcquireLockException("Lock wait timeout"))).isTrue();
        assertThat(TransientDataAccessFailures.isTransient(new QueryTimeoutException("timeout"))).isTrue();
        assertThat(TransientDataAccessFailures.isTransient(new DataAccessResourceFailureException("link"))).isTrue();
        assertThat(TransientDataAccessFailures.isTransient(new IllegalStateException("wrapped",
                new SQLTransientConnectionException("pool exhausted")))).isTrue();
        assertThat(TransientDataAccessFailures.isTransient(new ServletException("wrapped",
                new CannotAcquireLockException("deadlock")))).isTrue();

        assertThat(TransientDataAccessFailures.isTransient(new DataIntegrityViolationException("dup"))).isFalse();
        assertThat(TransientDataAccessFailures.isTransient(new BadSqlGrammarException("t", "sql", null))).isFalse();
        assertThat(TransientDataAccessFailures.isTransient(new IllegalStateException("bug"))).isFalse();
        assertThat(TransientDataAccessFailures.isTransient(null)).isFalse();
    }

    @Test
    void signupLoginAndMeReturn503OnTransientDatabaseFailureWithoutLeakingCauseToResponseOrLog() throws Exception {
        given(repository.insert(anyString(), anyString(), any(MemberRole.class)))
                .willThrow(new CannotAcquireLockException("Lock wait timeout exceeded; " + SECRET_SQL,
                        new SQLTransientConnectionException(SECRET_URL)));
        expect503(mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + SECRET_LOGIN + "\",\"password\":\"" + SECRET_PASSWORD + "\"}")));

        given(authenticationProvider.authenticate(any()))
                .willThrow(new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection: " + SECRET_URL));
        expect503(mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + SECRET_LOGIN + "\",\"password\":\"" + SECRET_PASSWORD + "\"}")));

        given(repository.findById(anyLong())).willThrow(new QueryTimeoutException("timeout: " + SECRET_SQL));
        AuthenticatedMember principal = new AuthenticatedMember(7L, SECRET_LOGIN, MemberRole.MEMBER);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER"))));
        expect503(mockMvc.perform(get("/api/v1/me")));

        assertOnlyFixedEvents(3, "category=DB_TRANSIENT status=503", "stage=HANDLER");
    }

    @Test
    void nonTransientFailureOnAuthPathStays500AndLogsOnlyFixedEvent() throws Exception {
        given(repository.insert(anyString(), anyString(), any(MemberRole.class)))
                .willThrow(new IllegalStateException("unexpected " + SECRET_SQL,
                        new BadSqlGrammarException("insert", SECRET_SQL, null)));

        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + SECRET_LOGIN + "\",\"password\":\"" + SECRET_PASSWORD + "\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("요청을 처리하지 못했습니다."));

        assertOnlyFixedEvents(1, "category=UNEXPECTED status=500", "stage=HANDLER");
    }

    @Test
    void guardFilterConvertsFilterStageFailuresOnAuthPathsAndLeavesOtherPathsUntouched() throws Exception {
        AuthFailureGuardFilter filter = new AuthFailureGuardFilter();

        // 세션 저장소(JDBC) 조회·커밋 실패처럼 MVC 처리기 밖에서 난 장애.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.addHeader("Set-Cookie", "HANJARI_SESSION=should-be-cleared");
        filter.doFilter(request, response, (req, res) -> {
            throw new ServletException("session commit", new CannotGetJdbcConnectionException(SECRET_URL));
        });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"" + AuthFailurePolicy.UNAVAILABLE_MESSAGE + "\"}");
        assertThat(response.getHeader("Set-Cookie")).isNull();
        assertNoSensitive(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));

        MockHttpServletRequest me = new MockHttpServletRequest("GET", "/api/v1/me");
        MockHttpServletResponse meResponse = new MockHttpServletResponse();
        filter.doFilter(me, meResponse, (req, res) -> {
            throw new IllegalStateException("bug " + SECRET_SQL);
        });
        assertThat(meResponse.getStatus()).isEqualTo(500);
        assertThat(meResponse.getContentAsString()).contains("\"code\":\"INTERNAL_ERROR\"");

        // 응답이 이미 커밋된 뒤의 장애는 다시 던지지 않고(컨테이너의 예외 원문 로그 방지) 고정 이벤트만 남긴다.
        MockHttpServletRequest committed = new MockHttpServletRequest("POST", "/api/v1/auth/logout");
        MockHttpServletResponse committedResponse = new MockHttpServletResponse();
        filter.doFilter(committed, committedResponse, (req, res) -> {
            res.getWriter().write("partial");
            res.flushBuffer();
            throw new CannotAcquireLockException("deadlock " + SECRET_SQL);
        });
        assertThat(committedResponse.getStatus()).isEqualTo(200);
        assertThat(committedResponse.getContentAsString()).isEqualTo("partial");

        assertOnlyFixedEvents(3, "event=" + AuthFailurePolicy.EVENT, "stage=FILTER");

        // 인증 경로가 아니면 개입하지 않는다(기존 영화 API 오류 계약 유지).
        MockHttpServletRequest movie = new MockHttpServletRequest("GET", "/api/v1/movies/1");
        assertThatThrownBy(() -> filter.doFilter(movie, new MockHttpServletResponse(), (req, res) -> {
            throw new DataAccessResourceFailureException("link");
        })).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(AuthFailurePolicy.isAuthPath(new MockHttpServletRequest("GET", "/api/v1/meeting"))).isFalse();
        assertThat(AuthFailurePolicy.isAuthPath(new MockHttpServletRequest("GET", "/api/v1/authx"))).isFalse();
        assertThat(AuthFailurePolicy.isAuthPath(new MockHttpServletRequest("POST", "/api/v1/auth/signup"))).isTrue();
    }

    @Test
    void authPathDetectionFollowsMvcRoutingForEncodedAndParameterizedPaths() throws Exception {
        for (String uri : List.of("/api/v1/me", "/api/v1/%6de", "/api/v1/%6D%65", "/api/v1/m%65/", "/api/v1/me;x=1",
                "/api/%76%31/me", "/%61pi/v1/me", "/api/v1/auth/%6cogin", "/api/v1/%61uth/signup",
                "/api/v1/auth;a=b/csrf", "/api//v1/me", "/api/v1/./me", "/api/v1/x/../me", "/api/v1/auth")) {
            assertThat(AuthFailurePolicy.isAuthPath(new MockHttpServletRequest("GET", uri))).as(uri).isTrue();
        }
        for (String uri : List.of("/api/v1/movies/1", "/api/v1/%6dovies/1", "/api/v1/meeting", "/api/v1/authx",
                "/api/v1/%6de%65ting", "/api/v1%2Fme", "/api/v2/me", "/me", "/")) {
            assertThat(AuthFailurePolicy.isAuthPath(new MockHttpServletRequest("GET", uri))).as(uri).isFalse();
        }
        MockHttpServletRequest withContext = new MockHttpServletRequest("GET", "/ctx/api/v1/%6de");
        withContext.setContextPath("/ctx");
        assertThat(AuthFailurePolicy.isAuthPath(withContext)).isTrue();

        // 인코딩된 경로에서 MVC 처리기 밖 장애가 나도 보호 필터가 정책 응답·고정 로그로 바꾼다.
        MockHttpServletResponse response = new MockHttpServletResponse();
        new AuthFailureGuardFilter().doFilter(new MockHttpServletRequest("GET", "/api/v1/%6de"), response,
                (req, res) -> {
                    throw new QueryTimeoutException("timeout: " + SECRET_SQL);
                });
        assertThat(response.getStatus()).isEqualTo(503);
        assertNoSensitive(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertOnlyFixedEvents(1, "category=DB_TRANSIENT status=503", "stage=FILTER");
    }

    private void expect503(ResultActions actions) throws Exception {
        actions.andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(AuthFailurePolicy.UNAVAILABLE_MESSAGE));
        assertNoSensitive(actions.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void assertOnlyFixedEvents(int expectedCount, String... expectedFragments) {
        assertThat(appender.list).hasSize(expectedCount);
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getThrowableProxy()).as("예외 객체를 로그에 넘기지 않는다").isNull();
            String message = event.getFormattedMessage();
            assertThat(message).matches("event=AUTH_REQUEST_FAILED stage=(HANDLER|FILTER) "
                    + "category=(DB_TRANSIENT|UNEXPECTED) status=(500|503)");
            for (String fragment : expectedFragments) {
                assertThat(message).contains(fragment);
            }
            assertNoSensitive(message);
            for (Object argument : event.getArgumentArray()) {
                assertThat(argument).isInstanceOfAny(String.class, Integer.class);
                assertNoSensitive(String.valueOf(argument));
            }
        }
    }

    private static void assertNoSensitive(String text) {
        for (String sensitive : SENSITIVE) {
            assertThat(text).as("must not contain %s", sensitive).doesNotContain(sensitive);
        }
    }
}
