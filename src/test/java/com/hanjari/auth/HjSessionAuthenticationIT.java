package com.hanjari.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanjari.testsupport.ConcurrentRunner;
import com.hanjari.testsupport.MutableClock;
import com.hanjari.testsupport.TestDataCleaner;
import com.hanjari.testsupport.TestDatabaseGuard;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * HJ-003: 실제 MySQL(hanjari_test)과 실제 HTTP 서버(임의 포트)로 회원·세션 인증을 검증한다.
 *
 * <p>쿠키·Origin·CSRF 헤더를 직접 다루는 HTTP 클라이언트를 사용하고, 판정은 응답과 함께 members·SPRING_SESSION
 * 테이블의 사후 상태로 한다. 절대 만료·요청 제한 창은 제어 가능한 Clock으로, 유휴 만료는 세션 행의 마지막 접근
 * 시각을 DB에서 과거로 옮겨 검증한다. 임의 sleep을 쓰지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "hanjari.security.allowed-origins=http://localhost:5173")
class HjSessionAuthenticationIT {

    private static final String ORIGIN = "http://localhost:5173";
    private static final String COOKIE = SessionCookieConfig.COOKIE_NAME;
    private static final String PASSWORD = "correct horse 12";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private static TestDatabaseGuard.Settings settings;

    @TestConfiguration
    static class ControlledClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2030-01-01T00:00:00Z"));
        }
    }

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        settings = TestDatabaseGuard.verifiedFromEnvironment();
        TestDatabaseGuard.register(registry, settings);
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MutableClock clock;

    @Autowired
    private ApplicationContext applicationContext;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void cleanAndOpenNewRateWindow() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            TestDataCleaner.cleanAll(connection);
        }
        // 요청 제한은 분 단위 창이다. 테스트마다 새 창에서 시작한다(시각은 항상 정각 분).
        clock.advance(Duration.ofHours(1));
    }

    // AC1 ---------------------------------------------------------------------------------------------

    @Test
    void signupStoresUniqueLoginIdAndArgon2idHashOnly() throws Exception {
        Browser browser = newBrowserWithCsrf();
        Reply reply = browser.post("/api/v1/auth/signup", credentials("member_one", PASSWORD));

        assertThat(reply.status()).isEqualTo(201);
        assertThat((Integer) reply.json("$.length()")).isEqualTo(2);
        long memberId = ((Number) reply.json("$.memberId")).longValue();
        assertThat((String) reply.json("$.loginId")).isEqualTo("member_one");
        assertThat(reply.body()).doesNotContain(PASSWORD).doesNotContain("argon2").doesNotContain("role");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, login_id, password_hash, role, created_at FROM members WHERE login_id = 'member_one'");
        assertThat(((Number) row.get("id")).longValue()).isEqualTo(memberId);
        String hash = (String) row.get("password_hash");
        assertThat(hash).startsWith("$argon2id$v=19$m=16384,t=2,p=1$").doesNotContain(PASSWORD).doesNotContain("horse");
        assertThat(row.get("role")).isEqualTo("MEMBER");
        assertThat(row.get("created_at")).isNotNull();

        // 같은 비밀번호라도 salt가 달라 해시가 다르다.
        assertThat(browser.post("/api/v1/auth/signup", credentials("member_two", PASSWORD)).status()).isEqualTo(201);
        String otherHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM members WHERE login_id = 'member_two'", String.class);
        assertThat(otherHash).isNotEqualTo(hash);

        // 저장 열은 로그인 ID·해시·역할·생성 시각뿐(추가 개인정보 없음). 고유 제약이 DB에 있다.
        assertThat(jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = 'members' ORDER BY ordinal_position", String.class))
                .containsExactly("id", "login_id", "password_hash", "role", "created_at");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = DATABASE() "
                        + "AND table_name = 'members' AND constraint_type = 'UNIQUE' "
                        + "AND constraint_name = 'uk_members_login_id'", Integer.class)).isEqualTo(1);
        // DB 제약도 평문·비 Argon2id 값을 거부한다.
        assertThat(insertRawFails("plain_text", PASSWORD)).isTrue();
        assertThat(count("members")).isEqualTo(2);
    }

    @Test
    void signupRejectsInvalidInputAndDuplicateWithoutStoringAnything() throws Exception {
        Browser browser = newBrowserWithCsrf();
        Map<String, String> invalid = new LinkedHashMap<>();
        invalid.put(credentials("abc", PASSWORD), "INVALID_LOGIN_ID");
        invalid.put(credentials("a".repeat(31), PASSWORD), "INVALID_LOGIN_ID");
        invalid.put(credentials("Member_one", PASSWORD), "INVALID_LOGIN_ID");
        invalid.put(credentials("member-one", PASSWORD), "INVALID_LOGIN_ID");
        invalid.put(credentials(" member_one", PASSWORD), "INVALID_LOGIN_ID");
        invalid.put(credentials("member_one", "a".repeat(11)), "INVALID_PASSWORD");
        invalid.put(credentials("member_one", "a".repeat(65)), "INVALID_PASSWORD");
        invalid.put("{\"loginId\":\"member_one\"}", "INVALID_REQUEST");
        invalid.put("{\"loginId\":12345,\"password\":\"" + PASSWORD + "\"}", "INVALID_REQUEST");
        invalid.put("{\"loginId\":", "INVALID_REQUEST");
        invalid.put("[]", "INVALID_REQUEST");
        invalid.put("null", "INVALID_REQUEST");
        for (Map.Entry<String, String> entry : invalid.entrySet()) {
            Reply reply = browser.post("/api/v1/auth/signup", entry.getKey());
            assertThat(reply.status()).as(entry.getKey()).isEqualTo(400);
            assertThat((String) reply.json("$.code")).as(entry.getKey()).isEqualTo(entry.getValue());
            assertThat(reply.body()).doesNotContain(PASSWORD);
        }
        assertThat(count("members")).isZero();

        assertThat(browser.post("/api/v1/auth/signup", credentials("member_one", PASSWORD)).status()).isEqualTo(201);
        Reply duplicate = browser.post("/api/v1/auth/signup", credentials("member_one", "another password 1"));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat((String) duplicate.json("$.code")).isEqualTo("LOGIN_ID_TAKEN");
        assertThat(count("members")).isEqualTo(1);
        // 기존 계정의 비밀번호는 바뀌지 않았다.
        assertThat(newBrowserWithCsrf().post("/api/v1/auth/login", credentials("member_one", PASSWORD)).status())
                .isEqualTo(200);
    }

    @Test
    void concurrentDuplicateSignupsCreateExactlyOneMember() throws Exception {
        int parties = 8;
        List<ConcurrentRunner.BarrierTask<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < parties; i++) {
            String password = "racing password " + i;
            tasks.add(gate -> {
                Browser browser = newBrowserWithCsrf(); // 준비(세션·CSRF)는 출발 전에 끝낸다.
                gate.await();
                return browser.post("/api/v1/auth/signup", credentials("race_member", password)).status();
            });
        }

        List<Integer> statuses = ConcurrentRunner.runWithGate(Duration.ofSeconds(90), tasks);

        // 판정 기준은 DB: 행이 정확히 하나. 응답은 201 하나와 409 나머지.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM members WHERE login_id = 'race_member'", Integer.class)).isEqualTo(1);
        assertThat(count("members")).isEqualTo(1);
        assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
        assertThat(statuses).filteredOn(status -> status == 409).hasSize(parties - 1);
        // 저장된 해시는 이긴 요청의 비밀번호와 일치한다(다른 요청이 덮어쓰지 않았다).
        int winner = statuses.indexOf(201);
        assertThat(newBrowserWithCsrf().post("/api/v1/auth/login",
                credentials("race_member", "racing password " + winner)).status()).isEqualTo(200);
        System.out.println("[HJ-003] concurrent signup statuses=" + statuses + ", rows=1");
    }

    // AC2 ---------------------------------------------------------------------------------------------

    @Test
    void loginFailureDoesNotRevealWhetherAccountExists() throws Exception {
        signup("known_user", "  spaced password  ");

        List<String> attempts = List.of(
                credentials("ghost_user", "  spaced password  "),   // 없는 ID
                credentials("known_user", "wrong password 123"),    // 틀린 비밀번호
                credentials("known_user", "spaced password"),       // trim된 비밀번호
                credentials("KNOWN_USER", "  spaced password  "),   // 대소문자 정규화 없음
                credentials("known_user", "short"));                // 형식 위반
        List<String> bodies = new ArrayList<>();
        for (String attempt : attempts) {
            Browser browser = newBrowserWithCsrf();
            Reply reply = browser.post("/api/v1/auth/login", attempt);
            assertThat(reply.status()).as(attempt).isEqualTo(401);
            assertThat(reply.header("Content-Type")).startsWith("application/json");
            bodies.add(reply.body());
            assertThat(browser.get("/api/v1/me").status()).isEqualTo(401);
        }
        assertThat(bodies).allSatisfy(body -> assertThat(body).isEqualTo(bodies.get(0)));
        assertThat((String) JsonPath.read(bodies.get(0), "$.code")).isEqualTo("LOGIN_FAILED");
        assertThat(bodies.get(0)).doesNotContain("known_user").doesNotContain("ghost_user");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME IS NOT NULL", Integer.class)).isZero();

        Browser exact = newBrowserWithCsrf();
        assertThat(exact.post("/api/v1/auth/login", credentials("known_user", "  spaced password  ")).status())
                .isEqualTo(200);
    }

    @Test
    void loginRotatesSessionIdAndCsrfTokenAndUsesLocalCookiePolicy() throws Exception {
        long memberId = signup("rotating_user", PASSWORD);
        Browser browser = newBrowserWithCsrf();
        String anonymousCookie = browser.sessionCookie;
        String anonymousCsrf = browser.csrfToken;
        String anonymousSessionId = decode(anonymousCookie);
        assertThat(sessionRowCount(anonymousSessionId)).isEqualTo(1);

        Reply login = browser.post("/api/v1/auth/login", credentials("rotating_user", PASSWORD));

        assertThat(login.status()).isEqualTo(200);
        assertThat(((Number) login.json("$.memberId")).longValue()).isEqualTo(memberId);
        assertThat((String) login.json("$.loginId")).isEqualTo("rotating_user");
        String setCookie = login.sessionSetCookie();
        assertThat(setCookie).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/")
                .doesNotContainIgnoringCase("Domain=").doesNotContainIgnoringCase("Secure");
        String authenticatedSessionId = decode(browser.sessionCookie);
        assertThat(authenticatedSessionId).isNotEqualTo(anonymousSessionId);
        assertThat(sessionRowCount(anonymousSessionId)).as("old session id no longer exists").isZero();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT PRINCIPAL_NAME, MAX_INACTIVE_INTERVAL FROM SPRING_SESSION WHERE SESSION_ID = ?",
                authenticatedSessionId);
        assertThat(row.get("PRINCIPAL_NAME")).as("only member id, never login id").isEqualTo(Long.toString(memberId));
        assertThat(((Number) row.get("MAX_INACTIVE_INTERVAL")).intValue()).isEqualTo(1800);

        Reply me = browser.get("/api/v1/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(((Number) me.json("$.memberId")).longValue()).isEqualTo(memberId);

        // 고정된(로그인 전) 세션 쿠키는 인증되지 않는다.
        Browser attacker = new Browser();
        attacker.sessionCookie = anonymousCookie;
        assertThat(attacker.get("/api/v1/me").status()).isEqualTo(401);

        // 로그인 전 CSRF 토큰도 교체되어 더 이상 쓸 수 없다.
        Reply staleCsrf = browser.postWithToken(SecurityConfig.LOGOUT_PATH, "", anonymousCsrf);
        assertThat(staleCsrf.status()).isEqualTo(403);
        assertThat((String) staleCsrf.json("$.code")).isEqualTo("CSRF_INVALID");
        assertThat(browser.get("/api/v1/me").status()).as("still logged in").isEqualTo(200);
    }

    // AC3 ---------------------------------------------------------------------------------------------

    @Test
    void logoutDestroysServerSessionAndClearsCookieAndIsIdempotent() throws Exception {
        signup("leaving_user", PASSWORD);
        Browser browser = loggedIn("leaving_user", PASSWORD);
        String cookie = browser.sessionCookie;
        String csrf = browser.csrfToken;
        String sessionId = decode(cookie);

        Reply logout = browser.post(SecurityConfig.LOGOUT_PATH, "");

        assertThat(logout.status()).isEqualTo(204);
        assertThat(logout.sessionSetCookie()).startsWith(COOKIE + "=;").contains("Max-Age=0");
        assertThat(sessionRowCount(sessionId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME IS NOT NULL", Integer.class)).isZero();

        Browser replay = new Browser();
        replay.sessionCookie = cookie;
        assertThat(replay.get("/api/v1/me").status()).as("replayed cookie after logout").isEqualTo(401);
        // 폐기된 세션의 CSRF 토큰으로는 로그아웃도 거부된다(403). 새 토큰을 받으면 다시 204.
        assertThat(replay.postWithToken(SecurityConfig.LOGOUT_PATH, "", csrf).status()).isEqualTo(403);
        Browser again = newBrowserWithCsrf();
        assertThat(again.post(SecurityConfig.LOGOUT_PATH, "").status()).isEqualTo(204);
        assertThat(again.get("/api/v1/me").status()).isEqualTo(401);
    }

    @Test
    void idleSessionExpiresAfterThirtyMinutesWithoutActivity() throws Exception {
        signup("idle_user", PASSWORD);
        Browser browser = loggedIn("idle_user", PASSWORD);
        String sessionId = decode(browser.sessionCookie);

        // 29분 무활동: 유효. 접근하면 마지막 접근 시각이 갱신된다.
        long twentyNineMinutesAgo = shiftLastAccess(sessionId, Duration.ofMinutes(29));
        assertThat(browser.get("/api/v1/me").status()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT LAST_ACCESS_TIME FROM SPRING_SESSION WHERE SESSION_ID = ?", Long.class, sessionId))
                .isGreaterThan(twentyNineMinutesAgo);

        // 30분 이상 무활동: 거부되고 서버 세션 행이 삭제된다.
        shiftLastAccess(sessionId, Duration.ofMinutes(30).plusSeconds(1));
        assertThat(browser.get("/api/v1/me").status()).isEqualTo(401);
        assertThat(sessionRowCount(sessionId)).isZero();
    }

    @Test
    void absoluteSessionLifetimeEndsTwelveHoursAfterLoginEvenWhenActive() throws Exception {
        signup("long_user", PASSWORD);
        Instant loginAt = clock.instant();
        Browser browser = loggedIn("long_user", PASSWORD);
        String sessionId = decode(browser.sessionCookie);

        clock.set(loginAt.plus(Duration.ofHours(6)));
        assertThat(browser.get("/api/v1/me").status()).isEqualTo(200);
        clock.set(loginAt.plus(Duration.ofHours(12)).minusMillis(1));
        assertThat(browser.get("/api/v1/me").status()).as("just before 12h").isEqualTo(200);

        clock.set(loginAt.plus(Duration.ofHours(12)));
        Reply expired = browser.get("/api/v1/me");
        assertThat(expired.status()).as("at exactly 12h").isEqualTo(401);
        assertThat((String) expired.json("$.code")).isEqualTo("UNAUTHENTICATED");
        assertThat(sessionRowCount(sessionId)).isZero();
        assertThat(browser.get("/api/v1/me").status()).isEqualTo(401);

        // 다시 로그인하면 새 12시간이 시작된다.
        clock.advance(Duration.ofMinutes(1));
        Browser fresh = loggedIn("long_user", PASSWORD);
        assertThat(fresh.get("/api/v1/me").status()).isEqualTo(200);
    }

    // AC4 ---------------------------------------------------------------------------------------------

    @Test
    void csrfTokenIsRequiredForSignupLoginAndLogout() throws Exception {
        signup("csrf_user", PASSWORD);
        Browser loggedIn = loggedIn("csrf_user", PASSWORD);
        Browser other = newBrowserWithCsrf();
        Browser browser = newBrowserWithCsrf();

        for (String token : new String[] {null, "not-a-token", other.csrfToken}) {
            Reply signup = browser.postWithToken("/api/v1/auth/signup", credentials("csrf_new", PASSWORD), token);
            assertThat(signup.status()).as("signup token=%s", token).isEqualTo(403);
            assertThat((String) signup.json("$.code")).isEqualTo("CSRF_INVALID");

            Reply login = browser.postWithToken("/api/v1/auth/login", credentials("csrf_user", PASSWORD), token);
            assertThat(login.status()).as("login token=%s", token).isEqualTo(403);
            assertThat((String) login.json("$.code")).isEqualTo("CSRF_INVALID");
            assertThat(browser.get("/api/v1/me").status()).isEqualTo(401);

            Reply logout = loggedIn.postWithToken(SecurityConfig.LOGOUT_PATH, "", token);
            assertThat(logout.status()).as("logout token=%s", token).isEqualTo(403);
            assertThat(loggedIn.get("/api/v1/me").status()).as("session survives forged logout").isEqualTo(200);
        }
        // 세션이 없는 요청(쿠키 없음)도 거부된다.
        Browser cookieless = new Browser();
        assertThat(cookieless.postWithToken("/api/v1/auth/signup", credentials("csrf_new", PASSWORD), null).status())
                .isEqualTo(403);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM members WHERE login_id = 'csrf_new'", Integer.class)).isZero();
        assertThat(count("members")).isEqualTo(1);
    }

    @Test
    void stateChangingRequestsRequireConfiguredLocalOrigin() throws Exception {
        signup("origin_user", PASSWORD);
        Browser browser = newBrowserWithCsrf();

        for (String origin : new String[] {null, "null", "http://evil.example", "http://localhost:5173.evil.example",
                "https://localhost:5173", "http://localhost:8080"}) {
            Reply signup = browser.send("POST", "/api/v1/auth/signup", credentials("origin_new", PASSWORD),
                    origin, browser.csrfToken);
            assertThat(signup.status()).as("signup origin=%s", origin).isEqualTo(403);
            assertThat((String) signup.json("$.code")).isEqualTo("ORIGIN_NOT_ALLOWED");

            Reply login = browser.send("POST", "/api/v1/auth/login", credentials("origin_user", PASSWORD),
                    origin, browser.csrfToken);
            assertThat(login.status()).as("login origin=%s", origin).isEqualTo(403);
            assertThat((String) login.json("$.code")).isEqualTo("ORIGIN_NOT_ALLOWED");
        }
        assertThat(browser.get("/api/v1/me").status()).isEqualTo(401);
        assertThat(count("members")).isEqualTo(1);
        // 조회(GET)는 Origin 없이 허용된다.
        assertThat(new Browser().get("/api/v1/movies/1").status()).isEqualTo(404);

        assertThat(browser.post("/api/v1/auth/login", credentials("origin_user", PASSWORD)).status()).isEqualTo(200);
    }

    @Test
    void loginAttemptsAreLimitedPerLoginIdAndPerInstanceWith429() throws Exception {
        signup("limited_user", PASSWORD);
        signup("bystander", PASSWORD);
        Browser browser = newBrowserWithCsrf();

        for (int i = 0; i < 5; i++) {
            assertThat(browser.post("/api/v1/auth/login", credentials("limited_user", "wrong password " + i)).status())
                    .isEqualTo(401);
        }
        Reply limited = browser.post("/api/v1/auth/login", credentials("limited_user", PASSWORD));
        assertThat(limited.status()).as("6th attempt, even with correct password").isEqualTo(429);
        assertThat((String) limited.json("$.code")).isEqualTo("LOGIN_RATE_LIMITED");
        assertThat(Integer.parseInt(limited.header("Retry-After"))).isBetween(1, 60);
        assertThat(browser.get("/api/v1/me").status()).isEqualTo(401);
        // 없는 ID도 같은 방식으로 제한된다(존재 여부를 드러내지 않음).
        for (int i = 0; i < 5; i++) {
            browser.post("/api/v1/auth/login", credentials("ghost_limited", "wrong password " + i));
        }
        Reply ghost = browser.post("/api/v1/auth/login", credentials("ghost_limited", PASSWORD));
        assertThat(ghost.status()).isEqualTo(429);
        assertThat(ghost.body()).isEqualTo(limited.body());
        // 다른 ID는 영향이 없다.
        assertThat(newBrowserWithCsrf().post("/api/v1/auth/login", credentials("bystander", PASSWORD)).status())
                .isEqualTo(200);

        clock.advance(Duration.ofMinutes(1));
        assertThat(browser.post("/api/v1/auth/login", credentials("limited_user", PASSWORD)).status())
                .as("next minute window").isEqualTo(200);

        // 인스턴스 전체: 새 창에서 서로 다른 ID로 100회 후 101번째는 정상 계정이어도 429.
        clock.advance(Duration.ofMinutes(1));
        Browser flood = newBrowserWithCsrf();
        for (int i = 0; i < 100; i++) {
            assertThat(flood.post("/api/v1/auth/login", credentials(String.format("flood_%03d", i), PASSWORD)).status())
                    .as("attempt %d", i).isEqualTo(401);
        }
        Reply global = newBrowserWithCsrf().post("/api/v1/auth/login", credentials("bystander", PASSWORD));
        assertThat(global.status()).isEqualTo(429);
        assertThat((String) global.json("$.code")).isEqualTo("LOGIN_RATE_LIMITED");
    }

    // AC5 ---------------------------------------------------------------------------------------------

    @Test
    void publicSignupCannotObtainOperatorRoleAndNoAccountIsSeeded() throws Exception {
        // 초기 데이터·기본 사용자로 만들어진 계정이 없다.
        assertThat(applicationContext.getBeansOfType(UserDetailsService.class)).isEmpty();
        Pattern seedsMembers = Pattern.compile("(?is)insert\\s+(ignore\\s+)?into\\s+`?(members|spring_session)");
        Resource[] scripts = new PathMatchingResourcePatternResolver().getResources("classpath*:db/**/*.sql");
        assertThat(scripts).isNotEmpty();
        for (Resource script : scripts) {
            String sql = script.getContentAsString(StandardCharsets.UTF_8);
            assertThat(seedsMembers.matcher(sql).find()).as(script.getFilename()).isFalse();
        }

        Browser browser = newBrowserWithCsrf();
        for (String role : new String[] {"\"OPERATOR\"", "\"MEMBER\"", "null", "[\"OPERATOR\"]"}) {
            Reply reply = browser.post("/api/v1/auth/signup",
                    "{\"loginId\":\"wannabe_op\",\"password\":\"" + PASSWORD + "\",\"role\":" + role + "}");
            assertThat(reply.status()).isEqualTo(400);
            assertThat((String) reply.json("$.code")).isEqualTo("ROLE_NOT_ALLOWED");
        }
        for (String field : new String[] {"email", "name", "phone", "birthDate"}) {
            Reply reply = browser.post("/api/v1/auth/signup",
                    "{\"loginId\":\"wannabe_op\",\"password\":\"" + PASSWORD + "\",\"" + field + "\":\"x\"}");
            assertThat(reply.status()).as(field).isEqualTo(400);
            assertThat((String) reply.json("$.code")).isEqualTo("UNSUPPORTED_FIELD");
        }
        assertThat(count("members")).isZero();

        long memberId = signup("plain_member", PASSWORD);
        assertThat(jdbcTemplate.queryForObject("SELECT role FROM members WHERE id = ?", String.class, memberId))
                .isEqualTo("MEMBER");

        Browser anonymous = newBrowserWithCsrf();
        Reply anonymousOps = anonymous.post("/api/v1/ops/movies", "{}");
        assertThat(anonymousOps.status()).isEqualTo(401);
        assertThat((String) anonymousOps.json("$.code")).isEqualTo("UNAUTHENTICATED");

        Browser member = loggedIn("plain_member", PASSWORD);
        Reply memberOps = member.post("/api/v1/ops/movies", "{}");
        assertThat(memberOps.status()).isEqualTo(403);
        assertThat((String) memberOps.json("$.code")).isEqualTo("FORBIDDEN");
        assertThat(member.get("/api/v1/ops/screenings").status()).isEqualTo(403);

        // 대조: 격리된 테스트 DB에서만 역할을 직접 바꾼 fixture는 권한 검사를 통과한다(라우트가 없어 404).
        jdbcTemplate.update("UPDATE members SET role = 'OPERATOR' WHERE id = ?", memberId);
        Browser operator = loggedIn("plain_member", PASSWORD);
        assertThat(operator.post("/api/v1/ops/movies", "{}").status()).isEqualTo(404);
    }

    // helpers -----------------------------------------------------------------------------------------

    private long signup(String loginId, String password) throws Exception {
        Reply reply = newBrowserWithCsrf().post("/api/v1/auth/signup", credentials(loginId, password));
        assertThat(reply.status()).as("signup %s", loginId).isEqualTo(201);
        return ((Number) reply.json("$.memberId")).longValue();
    }

    private Browser loggedIn(String loginId, String password) throws Exception {
        Browser browser = newBrowserWithCsrf();
        assertThat(browser.post("/api/v1/auth/login", credentials(loginId, password)).status()).isEqualTo(200);
        browser.fetchCsrf(); // 로그인으로 교체된 토큰을 다시 받는다.
        return browser;
    }

    private Browser newBrowserWithCsrf() throws Exception {
        Browser browser = new Browser();
        browser.fetchCsrf();
        return browser;
    }

    private static String credentials(String loginId, String password) {
        return "{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\"}";
    }

    private static String decode(String cookieValue) {
        return new String(Base64.getDecoder().decode(cookieValue), StandardCharsets.UTF_8);
    }

    private int sessionRowCount(String sessionId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE SESSION_ID = ?",
                Integer.class, sessionId);
    }

    /** 세션 행의 마지막 접근 시각을 실제 현재 시각 기준 {@code ago} 전으로 옮긴다. 옮긴 값을 반환한다. */
    private long shiftLastAccess(String sessionId, Duration ago) {
        long lastAccess = System.currentTimeMillis() - ago.toMillis();
        int updated = jdbcTemplate.update(
                "UPDATE SPRING_SESSION SET LAST_ACCESS_TIME = ?, EXPIRY_TIME = ? + MAX_INACTIVE_INTERVAL * 1000 "
                        + "WHERE SESSION_ID = ?", lastAccess, lastAccess, sessionId);
        assertThat(updated).isEqualTo(1);
        return lastAccess;
    }

    private boolean insertRawFails(String loginId, String passwordHash) {
        try {
            jdbcTemplate.update("INSERT INTO members (login_id, password_hash, role, created_at) "
                    + "VALUES (?, ?, 'MEMBER', UTC_TIMESTAMP(6))", loginId, passwordHash);
            return false;
        } catch (org.springframework.dao.DataAccessException rejected) {
            return true;
        }
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Integer.class);
    }

    private record Reply(int status, String body, Map<String, List<String>> headers) {
        Object json(String path) {
            return JsonPath.read(body, path);
        }

        String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst().orElse(null);
        }

        String sessionSetCookie() {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase("Set-Cookie"))
                    .flatMap(entry -> entry.getValue().stream())
                    .filter(value -> value.startsWith(COOKIE + "="))
                    .reduce((first, last) -> last)
                    .orElseThrow(() -> new AssertionError("no " + COOKIE + " Set-Cookie header"));
        }
    }

    /** 세션 쿠키 하나와 CSRF 토큰을 직접 관리하는 최소 HTTP 클라이언트. */
    private final class Browser {
        String sessionCookie;
        String csrfToken;
        String csrfHeader = "X-CSRF-TOKEN";

        void fetchCsrf() throws IOException, InterruptedException {
            Reply reply = get("/api/v1/auth/csrf");
            assertThat(reply.status()).isEqualTo(200);
            csrfHeader = (String) reply.json("$.headerName");
            csrfToken = (String) reply.json("$.token");
            assertThat(sessionCookie).as("csrf endpoint issues a session cookie").isNotNull();
        }

        Reply get(String path) throws IOException, InterruptedException {
            return send("GET", path, null, null, null);
        }

        Reply post(String path, String json) throws IOException, InterruptedException {
            return send("POST", path, json, ORIGIN, csrfToken);
        }

        Reply postWithToken(String path, String json, String token) throws IOException, InterruptedException {
            return send("POST", path, json, ORIGIN, token);
        }

        Reply send(String method, String path, String json, String origin, String token)
                throws IOException, InterruptedException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(HTTP_TIMEOUT)
                    .method(method, json == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            if (json != null) {
                builder.header("Content-Type", "application/json");
            }
            if (origin != null) {
                builder.header("Origin", origin);
            }
            if (token != null) {
                builder.header(csrfHeader, token);
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
            return new Reply(response.statusCode(), response.body(), response.headers().map());
        }
    }
}
