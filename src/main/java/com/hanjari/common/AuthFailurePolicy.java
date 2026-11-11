package com.hanjari.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;

/**
 * 인증 경로(/api/v1/auth/**, /api/v1/me)의 장애 응답·로그 정책.
 *
 * <ul>
 *   <li>일시적 DB 장애 → 503 SERVICE_UNAVAILABLE, 그 밖의 예기치 않은 장애 → 500 INTERNAL_ERROR.</li>
 *   <li>로그는 고정 이벤트 코드·분류·상태 코드만 남긴다. 예외 객체·메시지·SQL·스택·요청 값은 남기지 않는다.</li>
 * </ul>
 */
public final class AuthFailurePolicy {

    public static final String EVENT = "AUTH_REQUEST_FAILED";
    public static final String UNAVAILABLE_CODE = "SERVICE_UNAVAILABLE";
    public static final String UNAVAILABLE_MESSAGE = "일시적으로 요청을 처리할 수 없습니다. 잠시 후 같은 요청을 다시 시도하세요.";
    public static final String INTERNAL_CODE = "INTERNAL_ERROR";
    public static final String INTERNAL_MESSAGE = "요청을 처리하지 못했습니다.";

    private static final Logger log = LoggerFactory.getLogger(AuthFailurePolicy.class);

    /** 장애 분류 결과. code·message는 고정 상수다. */
    public record Outcome(HttpStatus status, String code, String message, String category) {
    }

    private AuthFailurePolicy() {
    }

    /**
     * 인증 경로인지 판별한다. 원시 URI 문자열을 비교하지 않고 Spring MVC 라우팅과 같은 방식으로 경로를 해석한다:
     * {@link RequestPath}로 세그먼트를 나누고 각 세그먼트의 퍼센트 디코딩 값(matrix 변수 제외)을 비교한다.
     * 따라서 {@code /api/v1/%6de}, {@code /api/v1/me;x=1}처럼 MVC가 /api/v1/me로 라우팅하는 요청도 인증 경로다.
     * 빈 세그먼트와 "."은 건너뛰고 ".."은 상위로 올린다(판별을 넓히는 쪽으로만 정규화한다).
     */
    public static boolean isAuthPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        List<String> segments;
        try {
            String contextPath = request.getContextPath();
            if (contextPath == null || !uri.startsWith(contextPath)) {
                contextPath = "";
            }
            segments = decodedSegments(RequestPath.parse(uri, contextPath).pathWithinApplication());
        } catch (RuntimeException unparsable) {
            // 해석할 수 없는 경로는 MVC도 라우팅하지 못한다. 보수적으로 인증 경로 정책(최소 로그)을 적용한다.
            return true;
        }
        if (segments.size() < 3 || !segments.get(0).equals("api") || !segments.get(1).equals("v1")) {
            return false;
        }
        String third = segments.get(2);
        return third.equals("auth") || third.equals("me");
    }

    private static List<String> decodedSegments(PathContainer path) {
        Deque<String> segments = new ArrayDeque<>();
        for (PathContainer.Element element : path.elements()) {
            if (element instanceof PathContainer.PathSegment segment) {
                String value = segment.valueToMatch();
                if (value.isEmpty() || value.equals(".")) {
                    continue;
                }
                if (value.equals("..")) {
                    segments.pollLast();
                    continue;
                }
                segments.addLast(value);
            }
        }
        return new ArrayList<>(segments);
    }

    public static Outcome classify(Throwable failure) {
        if (TransientDataAccessFailures.isTransient(failure)) {
            return new Outcome(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_CODE, UNAVAILABLE_MESSAGE, "DB_TRANSIENT");
        }
        return new Outcome(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_CODE, INTERNAL_MESSAGE, "UNEXPECTED");
    }

    /** 허용 필드만 기록한다. stage는 이 코드베이스의 고정 상수(HANDLER·FILTER)만 쓴다. */
    public static void record(Outcome outcome, String stage) {
        if (outcome.status().is5xxServerError() && outcome.status() != HttpStatus.SERVICE_UNAVAILABLE) {
            log.error("event={} stage={} category={} status={}", EVENT, stage, outcome.category(),
                    outcome.status().value());
        } else {
            log.warn("event={} stage={} category={} status={}", EVENT, stage, outcome.category(),
                    outcome.status().value());
        }
    }
}
