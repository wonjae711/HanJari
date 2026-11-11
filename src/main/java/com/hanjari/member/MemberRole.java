package com.hanjari.member;

/** 공개 가입은 항상 MEMBER다. OPERATOR는 별도 승인된 수동 절차로만 부여하며 이를 위한 API는 없다. */
public enum MemberRole {
    MEMBER,
    OPERATOR
}
