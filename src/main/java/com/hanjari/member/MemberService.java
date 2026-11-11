package com.hanjari.member;

import com.hanjari.auth.CredentialPolicy;
import com.hanjari.common.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 공개 회원가입. 역할은 항상 MEMBER이며 입력으로 바꿀 수 없다. */
@Service
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    public MemberService(MemberRepository memberRepository, PasswordEncoder passwordEncoder) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public record SignupResult(long memberId, String loginId) {
    }

    public SignupResult signup(CredentialPolicy.Credentials credentials) {
        CredentialPolicy.requireSignupFormat(credentials);
        String hash = passwordEncoder.encode(credentials.password());
        try {
            long id = memberRepository.insert(credentials.loginId(), hash, MemberRole.MEMBER);
            return new SignupResult(id, credentials.loginId());
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "LOGIN_ID_TAKEN", "이미 사용 중인 로그인 ID입니다.");
        }
    }
}
