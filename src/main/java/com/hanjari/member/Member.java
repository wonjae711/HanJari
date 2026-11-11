package com.hanjari.member;

/** 회원 행. toString은 비밀번호 해시를 노출하지 않는다. */
public record Member(long id, String loginId, String passwordHash, MemberRole role) {

    @Override
    public String toString() {
        return "Member[id=" + id + ", role=" + role + "]";
    }
}
