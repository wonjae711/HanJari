package com.hanjari.auth;

import com.hanjari.member.Member;
import com.hanjari.member.MemberRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 로그인 ID·비밀번호 검증.
 *
 * <p>없는 ID·형식 위반·틀린 비밀번호는 모두 같은 {@link BadCredentialsException}으로 실패한다. 없는 ID에서도
 * 고정 더미 해시로 Argon2 검증을 한 번 수행해 처리 경로를 맞춘다. 이 빈이 등록되어 있어 Spring Boot의 기본
 * 메모리 사용자(초기 계정)가 만들어지지 않는다.
 */
@Component
public class MemberAuthenticationProvider implements AuthenticationProvider {

    static final String FAILURE = "LOGIN_FAILED";
    private static final String DUMMY_INPUT = "hanjari-dummy-password";

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final String dummyHash;

    public MemberAuthenticationProvider(MemberRepository memberRepository, PasswordEncoder passwordEncoder) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode(DUMMY_INPUT);
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String loginId = authentication.getName();
        Object credentials = authentication.getCredentials();
        String password = credentials instanceof String value ? value : null;

        boolean wellFormed = CredentialPolicy.isValidLoginId(loginId) && CredentialPolicy.isValidPassword(password);
        Optional<Member> member = wellFormed ? memberRepository.findByLoginId(loginId) : Optional.empty();
        if (member.isEmpty()) {
            // 길이 제한을 벗어난 입력은 해시 비용을 키우지 않도록 고정 입력으로 대신 검증한다.
            passwordEncoder.matches(wellFormed ? password : DUMMY_INPUT, dummyHash);
            throw new BadCredentialsException(FAILURE);
        }
        if (!passwordEncoder.matches(password, member.get().passwordHash())) {
            throw new BadCredentialsException(FAILURE);
        }
        Member found = member.get();
        AuthenticatedMember principal = new AuthenticatedMember(found.id(), found.loginId(), found.role());
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + found.role().name())));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
