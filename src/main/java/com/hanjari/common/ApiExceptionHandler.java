package com.hanjari.common;

import com.hanjari.movie.InvalidMovieIdException;
import com.hanjari.movie.MovieNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 공통 오류 응답. Spring MVC 표준 예외(없는 경로, 허용되지 않은 메서드 등)는 상위 클래스가
 * 원래 상태 코드로 처리하고, 그 밖의 예외만 INTERNAL_ERROR로 숨긴다.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String AUTH_PACKAGE = "com.hanjari.auth";
    private static final String CATALOG_PACKAGE = "com.hanjari.catalog";
    private static final String OPERATIONS_PACKAGE = "com.hanjari.operations";

    @ExceptionHandler(InvalidMovieIdException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidMovieId(InvalidMovieIdException ex) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_MOVIE_ID", "유효하지 않은 영화 ID입니다.");
    }

    @ExceptionHandler(MovieNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleMovieNotFound(MovieNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "MOVIE_NOT_FOUND", "영화를 찾을 수 없습니다.");
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException ex) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.getStatus()).contentType(MediaType.APPLICATION_JSON);
        ex.getHeaders().forEach((name, value) -> builder.header(name, value));
        return builder.body(new ApiErrorResponse(ex.getCode(), ex.getMessage()));
    }

    // 해석할 수 없는 본문은 {code,message}로 응답한다. 원시 입력은 로그·응답에 남기지 않는다.
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse("INVALID_REQUEST", "요청 본문을 해석할 수 없습니다."));
    }

    /**
     * DB 접속 실패 등은 404로 바꾸지 않는다.
     *
     * <p>인증 경로(가입·로그인·로그아웃·CSRF·현재 회원)는 {@link AuthFailurePolicy}를 따른다: 일시적 DB 장애는 503,
     * 그 밖은 500이며 로그에는 고정 이벤트 코드만 남기고 예외 객체·메시지를 기록하지 않는다.
     * 기존 영화 API는 기존 계약(500 INTERNAL_ERROR)을 유지한다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        if (AuthFailurePolicy.isAuthPath(request) || handledByAuthController(request)) {
            AuthFailurePolicy.Outcome outcome = AuthFailurePolicy.classify(ex);
            AuthFailurePolicy.record(outcome, "HANDLER");
            return error(outcome.status(), outcome.code(), outcome.message());
        }
        if (handledByPackage(request, CATALOG_PACKAGE) || handledByPackage(request, OPERATIONS_PACKAGE)) {
            // HJ-004 공개 조회·운영 API: 일시적 DB 장애(데드락·잠금 대기 초과 등)는 전체 롤백 후 503, 그 밖은 500.
            // 로그에는 고정 이벤트만 남기고 예외 객체·메시지·SQL·요청 값을 넘기지 않는다.
            AuthFailurePolicy.Outcome outcome = AuthFailurePolicy.classify(ex);
            log.warn("event=CATALOG_REQUEST_FAILED category={} status={}", outcome.category(),
                    outcome.status().value());
            return error(outcome.status(), outcome.code(), outcome.message());
        }
        log.error("Unhandled exception while processing request", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "요청을 처리하지 못했습니다.");
    }

    /**
     * 경로 문자열과 무관하게, MVC가 실제로 선택한 처리기가 인증 패키지(com.hanjari.auth)의 컨트롤러면 인증 경로 정책을
     * 적용한다. 라우팅 결과 자체를 기준으로 하므로 경로 표기(인코딩 등)에 따른 우회가 없다.
     */
    private static boolean handledByAuthController(HttpServletRequest request) {
        return handledByPackage(request, AUTH_PACKAGE);
    }

    private static boolean handledByPackage(HttpServletRequest request, String basePackage) {
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return false;
        }
        String packageName = handlerMethod.getBeanType().getPackageName();
        return packageName.equals(basePackage) || packageName.startsWith(basePackage + ".");
    }

    private static ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(code, message));
    }
}
