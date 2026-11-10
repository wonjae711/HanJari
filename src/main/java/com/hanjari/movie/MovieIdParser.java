package com.hanjari.movie;

import java.util.regex.Pattern;

/**
 * 경로의 영화 ID를 해석한다. 1 ~ Long.MAX_VALUE의 십진 정수만 허용하며
 * 부호, 선행 0, 0, 숫자 이외 문자(유니코드 숫자 포함), 범위 초과는 거부한다.
 */
final class MovieIdParser {

    private static final Pattern DECIMAL_WITHOUT_LEADING_ZERO = Pattern.compile("[1-9][0-9]{0,18}");

    private MovieIdParser() {
    }

    static long parse(String raw) {
        if (raw == null || !DECIMAL_WITHOUT_LEADING_ZERO.matcher(raw).matches()) {
            throw new InvalidMovieIdException();
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            throw new InvalidMovieIdException();
        }
    }
}
