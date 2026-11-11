package com.hanjari.common;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * 경로·쿼리·JSON 본문 값의 엄격한 해석. 정규화(trim·부호·선행 0·문자열 숫자 변환)를 하지 않는다.
 * 실패하면 고정 문구의 400 {@link ApiException}을 던지고 입력 값을 메시지에 담지 않는다.
 */
public final class RequestValues {

    private static final Pattern POSITIVE = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern NON_NEGATIVE = Pattern.compile("0|[1-9][0-9]{0,8}");
    /**
     * UTC 'Z' 표기만, 소수 초는 DB 정밀도(마이크로초)까지만 허용한다. Instant.parse가 받아 주는 24:00·윤초(60초)는
     * 다른 시각으로 바뀌므로 형식 단계에서 거부한다.
     */
    private static final Pattern UTC_INSTANT = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\\.[0-9]{1,6})?Z");
    private static final DateTimeFormatter STRICT_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    private RequestValues() {
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    /** 1 ~ Long.MAX_VALUE 십진 문자열. */
    public static long positiveId(String raw, String code, String message) {
        if (raw == null || !POSITIVE.matcher(raw).matches()) {
            throw badRequest(code, message);
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException overflow) {
            throw badRequest(code, message);
        }
    }

    /** 생략 시 기본값, 그 외 0 ~ max의 십진 문자열. */
    public static int boundedInt(String raw, int defaultValue, int min, int max, String code, String message) {
        if (raw == null) {
            return defaultValue;
        }
        if (!NON_NEGATIVE.matcher(raw).matches()) {
            throw badRequest(code, message);
        }
        int value = Integer.parseInt(raw);
        if (value < min || value > max) {
            throw badRequest(code, message);
        }
        return value;
    }

    /** YYYY-MM-DD(존재하는 날짜만). */
    public static LocalDate date(String raw, String code, String message) {
        if (raw == null || !DATE.matcher(raw).matches()) {
            throw badRequest(code, message);
        }
        try {
            return LocalDate.parse(raw, STRICT_DATE);
        } catch (DateTimeParseException invalid) {
            throw badRequest(code, message);
        }
    }

    /** UTC ISO-8601 문자열('Z' 필수, 마이크로초 이하 정밀도). */
    public static Instant utcInstant(Object raw, String code, String message) {
        if (!(raw instanceof String text) || !UTC_INSTANT.matcher(text).matches()) {
            throw badRequest(code, message);
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException invalid) {
            throw badRequest(code, message);
        }
    }

    /**
     * JSON 정수만(Integer·Long·BigInteger). 실수·문자열·불리언·null은 거부한다. 범위 밖이면 거부한다.
     */
    public static long jsonInteger(Object raw, long min, long max, String code, String message) {
        long value;
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            value = ((Number) raw).longValue();
        } else if (raw instanceof BigInteger big && big.bitLength() < 64) {
            value = big.longValue();
        } else {
            throw badRequest(code, message);
        }
        if (value < min || value > max) {
            throw badRequest(code, message);
        }
        return value;
    }

    /** 본문이 JSON 객체인지, 허용 필드만 있는지 확인한다. */
    public static Map<String, Object> requireObject(Map<String, Object> body, Set<String> allowedFields) {
        if (body == null) {
            throw badRequest("INVALID_REQUEST", "요청 본문이 필요합니다.");
        }
        for (String field : body.keySet()) {
            if (!allowedFields.contains(field)) {
                throw badRequest("UNSUPPORTED_FIELD", "허용되지 않은 입력 항목이 있습니다.");
            }
        }
        return body;
    }
}
