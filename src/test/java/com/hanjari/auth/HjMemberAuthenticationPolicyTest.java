package com.hanjari.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hanjari.common.ApiException;
import com.hanjari.member.Member;
import com.hanjari.member.MemberRepository;
import com.hanjari.member.MemberRole;
import com.hanjari.member.MemberService;
import com.hanjari.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * HJ-003 회원·인증 정책의 DB 없는 단위 검증. 실제 MySQL·HTTP 세션 동작은 {@code HjSessionAuthenticationIT}에서 검증한다.
 */
class HjMemberAuthenticationPolicyTest {

    private static final PasswordEncoder ARGON2 = PasswordConfig.argon2id();

    @Test
    void loginIdPolicyAcceptsOnlyLowercaseAsciiDigitsAndUnderscoreFrom4To30() {
        assertThat(CredentialPolicy.isValidLoginId("abcd")).isTrue();
        assertThat(CredentialPolicy.isValidLoginId("a".repeat(30))).isTrue();
        assertThat(CredentialPolicy.isValidLoginId("user_01")).isTrue();
        assertThat(CredentialPolicy.isValidLoginId("____")).isTrue();

        for (String invalid : new String[] {null, "", "abc", "a".repeat(31), "Abcd", "ABCD", "ab-cd", "ab.cd",
                "ab cd", " abcd", "abcd ", "한글아이디", "ａｂｃｄ", "abcd\n", "abc\u0000d"}) {
            assertThat(CredentialPolicy.isValidLoginId(invalid)).as("loginId %s", invalid).isFalse();
        }
    }

    @Test
    void passwordPolicyCountsCodePointsFrom12To64WithoutTrimming() {
        assertThat(CredentialPolicy.isValidPassword("a".repeat(12))).isTrue();
        assertThat(CredentialPolicy.isValidPassword("a".repeat(64))).isTrue();
        assertThat(CredentialPolicy.isValidPassword("a".repeat(11))).isFalse();
        assertThat(CredentialPolicy.isValidPassword("a".repeat(65))).isFalse();
        assertThat(CredentialPolicy.isValidPassword(null)).isFalse();
        // 공백도 그대로 센다(trim 없음): 공백 2 + 10자 = 12자.
        assertThat(CredentialPolicy.isValidPassword(" " + "a".repeat(10) + " ")).isTrue();
        assertThat(CredentialPolicy.isValidPassword("            ")).isTrue();
        // 보조 평면 문자(UTF-16 2단위)는 1자로 센다: 64개 = 128 char 단위지만 허용.
        String emoji = "😀";
        assertThat(CredentialPolicy.isValidPassword(emoji.repeat(64))).isTrue();
        assertThat(CredentialPolicy.isValidPassword(emoji.repeat(65))).isFalse();
        assertThat(CredentialPolicy.isValidPassword(emoji.repeat(6))).as("6 code points").isFalse();

        CredentialPolicy.Credentials parsed = CredentialPolicy.parse(Map.of("loginId", "spaced", "password", "  pass word 1234  "));
        assertThat(parsed.password()).isEqualTo("  pass word 1234  ");
        assertThat(parsed.toString()).doesNotContain("pass word").doesNotContain("spaced");
    }

    @Test
    void parserRejectsRoleAndAdditionalPersonalFields() {
        for (Object role : new Object[] {"OPERATOR", "MEMBER", "ROLE_OPERATOR", null, List.of("OPERATOR")}) {
            Map<String, Object> body = new HashMap<>(Map.of("loginId", "member01", "password", "a".repeat(12)));
            body.put("role", role);
            assertThat(catchThrowableOfType(ApiException.class, () -> CredentialPolicy.parse(body)))
                    .satisfies(ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(ex.getCode()).isEqualTo("ROLE_NOT_ALLOWED");
                    });
        }
        for (String extra : new String[] {"email", "name", "phone", "phoneNumber", "birthDate", "authorities",
                "admin", "memberId", "id"}) {
            Map<String, Object> body = new HashMap<>(Map.of("loginId", "member01", "password", "a".repeat(12)));
            body.put(extra, "x");
            assertThat(catchThrowableOfType(ApiException.class, () -> CredentialPolicy.parse(body)))
                    .as(extra)
                    .satisfies(ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(ex.getCode()).isEqualTo("UNSUPPORTED_FIELD");
                        assertThat(ex.getMessage()).doesNotContain(extra);
                    });
        }
    }

    @Test
    void parserRequiresStringLoginIdAndPasswordAndSignupRejectsBadFormatWithoutNormalizing() {
        List<Map<String, Object>> malformed = new ArrayList<>();
        malformed.add(null);
        malformed.add(Map.of());
        malformed.add(Map.of("loginId", "member01"));
        malformed.add(Map.of("password", "a".repeat(12)));
        malformed.add(Map.of("loginId", 1234, "password", "a".repeat(12)));
        malformed.add(Map.of("loginId", "member01", "password", 123456789012L));
        malformed.add(Map.of("loginId", List.of("member01"), "password", "a".repeat(12)));
        for (Map<String, Object> body : malformed) {
            assertThat(catchThrowableOfType(ApiException.class, () -> CredentialPolicy.parse(body)))
                    .as(String.valueOf(body))
                    .extracting(ApiException::getCode).isEqualTo("INVALID_REQUEST");
        }

        assertThat(catchThrowableOfType(ApiException.class, () -> CredentialPolicy.requireSignupFormat(
                new CredentialPolicy.Credentials("Member01", "a".repeat(12)))))
                .extracting(ApiException::getCode).isEqualTo("INVALID_LOGIN_ID");
        assertThat(catchThrowableOfType(ApiException.class, () -> CredentialPolicy.requireSignupFormat(
                new CredentialPolicy.Credentials("member01", "a".repeat(11)))))
                .extracting(ApiException::getCode).isEqualTo("INVALID_PASSWORD");
        assertThat(catchThrowableOfType(ApiException.class, () -> CredentialPolicy.requireSignupFormat(
                new CredentialPolicy.Credentials("member01", "a".repeat(65)))))
                .extracting(ApiException::getCode).isEqualTo("INVALID_PASSWORD");
    }

    @Test
    void argon2idHashEmbedsParametersAndRandomSaltAndVerifiesOnlyExactPassword() {
        String password = "  correct horse 12  ";
        long started = System.nanoTime();
        String first = ARGON2.encode(password);
        long encodeMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        String second = ARGON2.encode(password);

        assertThat(first).startsWith("$argon2id$v=19$m=16384,t=2,p=1$");
        assertThat(first).isNotEqualTo(second); // salt가 매번 다르다.
        assertThat(first).doesNotContain(password).doesNotContain("correct horse");
        started = System.nanoTime();
        assertThat(ARGON2.matches(password, first)).isTrue();
        long matchMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertThat(ARGON2.matches(password, second)).isTrue();
        assertThat(ARGON2.matches(password.trim(), first)).as("trim하지 않는다").isFalse();
        assertThat(ARGON2.matches("  Correct horse 12  ", first)).isFalse();
        System.out.println("[HJ-003] argon2id params: m=" + PasswordConfig.MEMORY_KIB + "KiB, t=" + PasswordConfig.ITERATIONS
                + ", p=" + PasswordConfig.PARALLELISM + ", salt=" + PasswordConfig.SALT_LENGTH + "B, hash="
                + PasswordConfig.HASH_LENGTH + "B; measured encode=" + encodeMillis + "ms, match=" + matchMillis + "ms");
    }

    @Test
    void unknownLoginIdAndWrongPasswordFailIdenticallyAndBothRunHashVerification() {
        AtomicInteger matches = new AtomicInteger();
        PasswordEncoder counting = new PasswordEncoder() {
            @Override
            public String encode(CharSequence raw) {
                return ARGON2.encode(raw);
            }

            @Override
            public boolean matches(CharSequence raw, String encoded) {
                matches.incrementAndGet();
                return ARGON2.matches(raw, encoded);
            }
        };
        MemberRepository repository = mock(MemberRepository.class);
        String hash = ARGON2.encode("known password 1");
        given(repository.findByLoginId("known_user"))
                .willReturn(Optional.of(new Member(7L, "known_user", hash, MemberRole.MEMBER)));
        given(repository.findByLoginId("ghost_user")).willReturn(Optional.empty());
        MemberAuthenticationProvider provider = new MemberAuthenticationProvider(repository, counting);

        List<String[]> failing = List.of(
                new String[] {"ghost_user", "known password 1"},
                new String[] {"known_user", "wrong password 1"},
                new String[] {"known_user", "known password 1 "},
                new String[] {"KNOWN_USER", "known password 1"},
                new String[] {"known_user", "short"});
        for (String[] attempt : failing) {
            int before = matches.get();
            Throwable thrown = catchThrowableOfType(BadCredentialsException.class, () -> provider.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(attempt[0], attempt[1])));
            assertThat(thrown).as(attempt[0]).isNotNull().hasMessage(MemberAuthenticationProvider.FAILURE);
            assertThat(matches.get() - before).as("hash verification runs once for %s", attempt[0]).isEqualTo(1);
        }
        verify(repository, never()).findByLoginId("KNOWN_USER");

        Authentication ok = provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("known_user", "known password 1"));
        assertThat(ok.isAuthenticated()).isTrue();
        assertThat(ok.getName()).isEqualTo("7"); // 세션 PRINCIPAL_NAME에는 회원 ID만 남는다.
        assertThat(ok.getCredentials()).isNull();
        assertThat(ok.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_MEMBER");
        assertThat(ok.getPrincipal().toString()).doesNotContain("known_user");
    }

    @Test
    void signupAlwaysStoresMemberRoleWithArgon2HashAndMapsDuplicateToConflict() {
        MemberRepository repository = mock(MemberRepository.class);
        given(repository.insert(eq("new_member"), anyString(), eq(MemberRole.MEMBER))).willReturn(42L);
        MemberService service = new MemberService(repository, ARGON2);

        MemberService.SignupResult result = service.signup(new CredentialPolicy.Credentials("new_member", "a long password"));

        assertThat(result.memberId()).isEqualTo(42L);
        assertThat(result.loginId()).isEqualTo("new_member");
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MemberRole> role = ArgumentCaptor.forClass(MemberRole.class);
        verify(repository).insert(eq("new_member"), hash.capture(), role.capture());
        assertThat(role.getValue()).isEqualTo(MemberRole.MEMBER);
        assertThat(hash.getValue()).startsWith("$argon2id$").doesNotContain("a long password");

        given(repository.insert(eq("dup_member"), anyString(), eq(MemberRole.MEMBER)))
                .willThrow(new DuplicateKeyException("Duplicate entry"));
        assertThat(catchThrowableOfType(ApiException.class,
                () -> service.signup(new CredentialPolicy.Credentials("dup_member", "a long password"))))
                .satisfies(ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("LOGIN_ID_TAKEN");
                });
        assertThatThrownBy(() -> service.signup(new CredentialPolicy.Credentials("Bad-Id", "a long password")))
                .isInstanceOf(ApiException.class);
        verify(repository, never()).insert(eq("Bad-Id"), anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void loginRateLimiterAllowsFivePerLoginIdAndHundredGloballyPerMinuteWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2030-01-01T00:00:15Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(clock);

        for (int i = 0; i < LoginRateLimiter.PER_LOGIN_ID_PER_MINUTE; i++) {
            assertThat(limiter.tryAcquire("target_id").allowed()).isTrue();
        }
        LoginRateLimiter.Decision rejected = limiter.tryAcquire("target_id");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(45);
        assertThat(limiter.tryAcquire("other_id").allowed()).as("다른 ID는 영향 없음").isTrue();

        clock.set(Instant.parse("2030-01-01T00:00:59.999Z"));
        assertThat(limiter.tryAcquire("target_id").retryAfterSeconds()).isEqualTo(1);
        clock.set(Instant.parse("2030-01-01T00:01:00Z"));
        assertThat(limiter.tryAcquire("target_id").allowed()).as("새 분 창").isTrue();

        // 전체 제한: 같은 창에서 서로 다른 ID로 100회 허용 후 거부. 형식이 틀린 ID는 하나의 키로 센다.
        clock.set(Instant.parse("2030-01-01T00:02:00Z"));
        for (int i = 0; i < LoginRateLimiter.GLOBAL_PER_MINUTE; i++) {
            assertThat(limiter.tryAcquire(String.format("user_%03d", i)).allowed()).as("attempt %d", i).isTrue();
        }
        assertThat(limiter.tryAcquire("fresh_user").allowed()).isFalse();
        clock.advance(Duration.ofMinutes(1));
        for (int i = 0; i < LoginRateLimiter.PER_LOGIN_ID_PER_MINUTE; i++) {
            assertThat(limiter.tryAcquire("BAD-ID-" + i).allowed()).isTrue();
        }
        assertThat(limiter.tryAcquire("ANOTHER-BAD").allowed()).as("malformed IDs share one bucket").isFalse();
        assertThat(limiter.tryAcquire("fresh_user").allowed()).isTrue();
    }

    @Test
    void absoluteSessionLifetimeExpiresExactlyAtTwelveHours() {
        long loginAt = Instant.parse("2030-01-01T00:00:00Z").toEpochMilli();
        long twelveHours = Duration.ofHours(12).toMillis();

        assertThat(SessionLifetimeFilter.isAbsolutelyExpired(loginAt, loginAt)).isFalse();
        assertThat(SessionLifetimeFilter.isAbsolutelyExpired(loginAt, loginAt + twelveHours - 1)).isFalse();
        assertThat(SessionLifetimeFilter.isAbsolutelyExpired(loginAt, loginAt + twelveHours)).isTrue();
        assertThat(SessionLifetimeFilter.isAbsolutelyExpired(loginAt, loginAt + twelveHours + 1)).isTrue();
        assertThat(SessionLifetimeFilter.ABSOLUTE_TIMEOUT).isEqualTo(Duration.ofHours(12));
    }

    @Test
    void originPolicyRequiresExactConfiguredLocalOriginForStateChangingMethods() {
        LocalOriginFilter filter = new LocalOriginFilter(List.of("http://localhost:8080"));

        for (String method : new String[] {"GET", "HEAD", "OPTIONS"}) {
            assertThat(filter.isAllowed(method, null)).isTrue();
        }
        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
            assertThat(filter.isAllowed(method, "http://localhost:8080")).isTrue();
            for (String origin : new String[] {null, "", "null", "http://localhost:8081", "https://localhost:8080",
                    "http://127.0.0.1:8080", "http://localhost:8080.evil.example", "http://evil.example",
                    "http://localhost:8080/", "HTTP://LOCALHOST:8080"}) {
                assertThat(filter.isAllowed(method, origin)).as("%s %s", method, origin).isFalse();
            }
        }
    }
}
