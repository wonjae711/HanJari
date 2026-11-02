package com.hanjari.common;

import com.hanjari.movie.InvalidMovieIdException;
import com.hanjari.movie.MovieNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 공통 오류 응답. Spring MVC 표준 예외(없는 경로, 허용되지 않은 메서드 등)는 상위 클래스가
 * 원래 상태 코드로 처리하고, 그 밖의 예외만 INTERNAL_ERROR로 숨긴다.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidMovieIdException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidMovieId(InvalidMovieIdException ex) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_MOVIE_ID", "유효하지 않은 영화 ID입니다.");
    }

    @ExceptionHandler(MovieNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleMovieNotFound(MovieNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "MOVIE_NOT_FOUND", "영화를 찾을 수 없습니다.");
    }

    // DB 접속 실패 등은 404로 바꾸지 않는다. 상세 내용은 서버 로그에만 남긴다.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception while processing request", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "요청을 처리하지 못했습니다.");
    }

    private static ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(code, message));
    }
}
