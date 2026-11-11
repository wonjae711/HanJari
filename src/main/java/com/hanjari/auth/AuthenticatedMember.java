package com.hanjari.auth;

import com.hanjari.member.MemberRole;
import java.io.Serializable;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * 세션에 저장되는 인증 주체. {@link #getName()}은 회원 ID라서 Spring Session의 PRINCIPAL_NAME 열에는
 * 로그인 ID가 아니라 회원 ID만 기록된다.
 */
public record AuthenticatedMember(long memberId, String loginId, MemberRole role)
        implements AuthenticatedPrincipal, Serializable {

    @Override
    public String getName() {
        return Long.toString(memberId);
    }

    @Override
    public String toString() {
        return "AuthenticatedMember[memberId=" + memberId + "]";
    }
}
