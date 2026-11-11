package com.hanjari.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.hanjari.common.AuthFailurePolicy;
import com.hanjari.member.MemberRepository;
import com.hanjari.testsupport.TestDataCleaner;
import com.hanjari.testsupport.TestDatabaseGuard;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * HJ-003 리뷰 수정: MVC가 /api/v1/me로 라우팅하는 인코딩된 경로(예: /api/v1/%6de)에서도 일시적 DB 장애가
 * 503과 고정 이벤트 로그로 처리되는지 실제 MySQL·실제 HTTP 서버로 검증한다.
 *
 * <p>가입·로그인은 실제 DB로 수행하고, 현재 회원 조회(findById)에만 민감 문자열을 담은 장애를 주입한다.
 * 로그는 루트 로거에서 모두 수집해 예외 객체·민감 문자열이 어디에도 없는지 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "hanjari.security.allowed-origins=http://localhost:5173")
// spy bean 때문에 다른 IT와 공유되지 않는 전용 컨텍스트다. 클래스 종료 후 닫아 연결 풀과 내장 서버가
// 이후 IT 실행 동안 MySQL 연결을 계속 잡고 있지 않게 한다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HjAuthEncodedPathFailureIT {

    private static final String ORIGIN = "http://localhost:5173";
    private static final String COOKIE = SessionCookieConfig.COOKIE_NAME;
    private static final String LOGIN_ID = "encoded_path_user";
    private static final String PASSWORD = "encoded path pw 12";
    private static final String SECRET_SQL = "SELECT id, login_id, password_hash FROM members WHERE login_id = "
            + "'leaky_login_id' AND password_hash = '$argon2id$v=19$m=16384,t=2,p=1$c2FsdA$aGFzaA'";
    private static final List<String> SENSITIVE = List.of("leaky_login_id", LOGIN_ID, PASSWORD, "$argon2id$",
            "SELECT id", "QueryTimeoutException", "password_hash");

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        TestDatabaseGuard.register(registry, TestDatabaseGuard.verifiedFromEnvironment());
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private DataSource dataSource;

    @MockitoSpyBean
    private MemberRepository memberRepository;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private ListAppender<ILoggingEvent> appender;
    private String sessionCookie;
    private String csrfHeader;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            TestDataCleaner.cleanAll(connection);
        }
        reset(memberRepository);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(appender);
        reset(memberRepository);
    }

    @Test
    void encodedMePathReturns503AndLogsOnlyFixedEventOnTransientDatabaseFailure() throws Exception {
        fetchCsrf();
        assertThat(send("POST", "/api/v1/auth/signup", credentials()).statusCode()).isEqualTo(201);
        assertThat(send("POST", "/api/v1/auth/login", credentials()).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/me", null).statusCode()).isEqualTo(200);
        // MVC 라우팅과 같은지 대조: 인코딩된 경로도 장애가 없으면 정상 회원 응답이다.
        HttpResponse<String> encodedOk = send("GET", "/api/v1/%6de", null);
        assertThat(encodedOk.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(encodedOk.body(), "$.loginId")).isEqualTo(LOGIN_ID);
        appender.list.clear();

        doThrow(new QueryTimeoutException("Statement cancelled due to timeout: " + SECRET_SQL))
                .when(memberRepository).findById(anyLong());

        List<String> paths = List.of("/api/v1/%6de", "/api/v1/%6D%65", "/api/v1/m%65", "/api/v1/me");
        for (String path : paths) {
            HttpResponse<String> response = send("GET", path, null);
            assertThat(response.statusCode()).as(path).isEqualTo(503);
            assertThat(response.headers().firstValue("Content-Type")).get().asString()
                    .startsWith("application/json");
            assertThat((String) JsonPath.read(response.body(), "$.code")).isEqualTo("SERVICE_UNAVAILABLE");
            assertThat((String) JsonPath.read(response.body(), "$.message"))
                    .isEqualTo(AuthFailurePolicy.UNAVAILABLE_MESSAGE);
            assertThat((Integer) JsonPath.read(response.body(), "$.length()")).isEqualTo(2);
            assertNoSensitive(response.body());
        }

        List<ILoggingEvent> failures = appender.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("event=" + AuthFailurePolicy.EVENT))
                .toList();
        assertThat(failures).hasSize(paths.size());
        for (ILoggingEvent event : failures) {
            assertThat(event.getFormattedMessage())
                    .isEqualTo("event=AUTH_REQUEST_FAILED stage=HANDLER category=DB_TRANSIENT status=503");
        }
        // 수집된 모든 로그(컨테이너·프레임워크 포함)에 예외 객체·원문·민감 문자열이 없다.
        for (ILoggingEvent event : appender.list) {
            assertNoSensitive(event.getFormattedMessage());
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                assertNoSensitive(proxy.getClassName() + " " + proxy.getMessage());
            }
        }
    }

    private void fetchCsrf() throws Exception {
        HttpResponse<String> reply = send("GET", "/api/v1/auth/csrf", null);
        assertThat(reply.statusCode()).isEqualTo(200);
        csrfHeader = JsonPath.read(reply.body(), "$.headerName");
        csrfToken = JsonPath.read(reply.body(), "$.token");
    }

    private static String credentials() {
        return "{\"loginId\":\"" + LOGIN_ID + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    private HttpResponse<String> send(String method, String rawPath, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath))
                .timeout(Duration.ofSeconds(30))
                .method(method, json == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (json != null) {
            builder.header("Content-Type", "application/json");
            builder.header("Origin", ORIGIN);
            builder.header(csrfHeader, csrfToken);
        }
        if (sessionCookie != null) {
            builder.header("Cookie", COOKIE + "=" + sessionCookie);
        }
        HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        for (String setCookie : response.headers().allValues("Set-Cookie")) {
            if (setCookie.startsWith(COOKIE + "=")) {
                String value = setCookie.substring(COOKIE.length() + 1).split(";", 2)[0];
                sessionCookie = value.isEmpty() || setCookie.contains("Max-Age=0") ? null : value;
            }
        }
        // 로그인으로 CSRF 토큰이 교체되므로 로그인 직후 다시 받는다.
        if (rawPath.equals("/api/v1/auth/login") && response.statusCode() == 200) {
            fetchCsrf();
        }
        return response;
    }

    private static void assertNoSensitive(String text) {
        for (String sensitive : SENSITIVE) {
            assertThat(text).as("must not contain %s", sensitive).doesNotContain(sensitive);
        }
    }
}
