package com.hanjari.auth;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import com.hanjari.common.ApiException;

/**
 * 가입·로그인 입력 정책.
 *
 * <ul>
 *   <li>로그인 ID: 소문자 영문·숫자·밑줄 4~30자. 대소문자 변환 등 정규화하지 않는다.</li>
 *   <li>비밀번호: 12~64자(코드 포인트 기준). trim·정규화하지 않는다.</li>
 *   <li>본문 필드는 loginId·password만 허용한다. role과 그 밖의 필드(추가 개인정보 등)는 거부한다.</li>
 * </ul>
 */
public final class CredentialPolicy {

    public static final int LOGIN_ID_MIN = 4;
    public static final int LOGIN_ID_MAX = 30;
    public static final int PASSWORD_MIN = 12;
    public static final int PASSWORD_MAX = 64;

    private static final Pattern LOGIN_ID = Pattern.compile("[a-z0-9_]{" + LOGIN_ID_MIN + "," + LOGIN_ID_MAX + "}");
    private static final Set<String> ALLOWED_FIELDS = Set.of("loginId", "password");

    private CredentialPolicy() {
    }

    /** 원문 그대로의 입력. toString은 비밀번호를 노출하지 않는다. */
    public record Credentials(String loginId, String password) {
        @Override
        public String toString() {
            return "Credentials[loginId=<hidden>, password=<hidden>]";
        }
    }

    public static boolean isValidLoginId(String loginId) {
        return loginId != null && LOGIN_ID.matcher(loginId).matches();
    }

    public static boolean isValidPassword(String password) {
        if (password == null) {
            return false;
        }
        int length = password.codePointCount(0, password.length());
        return length >= PASSWORD_MIN && length <= PASSWORD_MAX;
    }

    /** 본문 구조만 검증한다(허용 필드·문자열 타입). 형식 검증은 가입과 로그인이 다르게 처리한다. */
    public static Credentials parse(Map<String, Object> body) {
        if (body == null) {
            throw invalidRequest();
        }
        if (body.containsKey("role")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROLE_NOT_ALLOWED", "역할은 지정할 수 없습니다.");
        }
        for (String field : body.keySet()) {
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FIELD", "허용되지 않은 입력 항목이 있습니다.");
            }
        }
        if (!(body.get("loginId") instanceof String loginId) || !(body.get("password") instanceof String password)) {
            throw invalidRequest();
        }
        return new Credentials(loginId, password);
    }

    /** 가입용 형식 검증. 위반 시 400. */
    public static Credentials requireSignupFormat(Credentials credentials) {
        if (!isValidLoginId(credentials.loginId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LOGIN_ID",
                    "로그인 ID는 소문자 영문·숫자·밑줄 4~30자여야 합니다.");
        }
        if (!isValidPassword(credentials.password())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "비밀번호는 12~64자여야 합니다.");
        }
        return credentials;
    }

    private static ApiException invalidRequest() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "loginId와 password 문자열이 필요합니다.");
    }
}
