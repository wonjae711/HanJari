package com.hanjari.operations;

import com.hanjari.common.RequestValues;
import com.hanjari.movie.AgeRating;
import com.hanjari.screening.ScreeningSchedule;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 운영 API 본문 정책. 허용 필드 외 항목·타입 불일치·범위 위반은 400이며, 문자열 숫자·실수·부호 변환 같은 정규화를 하지 않는다.
 *
 * <ul>
 *   <li>영화: 제목 1~200자(코드 포인트, 공백만은 불가), 상영시간 1~600분 정수, 관람등급은 기존 enum.</li>
 *   <li>회차: 양의 정수 movieId·theaterId, UTC ISO-8601 startsAt('Z', 마이크로초까지), 가격 0~1,000,000원 정수.</li>
 *   <li>수정: version(0 이상 정수) 필수, 변경 항목 1개 이상. 회차의 영화는 바꿀 수 없다.</li>
 * </ul>
 * 미래 시작·참조 존재·중첩·판매 잠금은 잠금을 얻은 뒤 서비스가 DB 기준으로 판정한다.
 */
public final class OpsRequestPolicy {

    public static final int TITLE_MAX = 200;

    private static final Set<String> MOVIE_CREATE_FIELDS = Set.of("title", "runtimeMinutes", "ageRating");
    private static final Set<String> MOVIE_PATCH_FIELDS = Set.of("title", "runtimeMinutes", "ageRating", "version");
    private static final Set<String> SCREENING_CREATE_FIELDS = Set.of("movieId", "theaterId", "startsAt", "basePrice");
    private static final Set<String> SCREENING_PATCH_FIELDS = Set.of("theaterId", "startsAt", "basePrice", "version");

    private OpsRequestPolicy() {
    }

    public record MovieCreate(String title, int runtimeMinutes, AgeRating ageRating) {
    }

    /** null 항목은 변경하지 않는다. */
    public record MoviePatch(long version, String title, Integer runtimeMinutes, AgeRating ageRating) {
    }

    public record ScreeningCreate(long movieId, long theaterId, Instant startsAt, long basePrice) {
    }

    /** null 항목은 변경하지 않는다. */
    public record ScreeningPatch(long version, Long theaterId, Instant startsAt, Long basePrice) {
    }

    public static MovieCreate movieCreate(Map<String, Object> body) {
        RequestValues.requireObject(body, MOVIE_CREATE_FIELDS);
        requirePresent(body, MOVIE_CREATE_FIELDS);
        return new MovieCreate(title(body.get("title")), runtime(body.get("runtimeMinutes")),
                ageRating(body.get("ageRating")));
    }

    public static MoviePatch moviePatch(Map<String, Object> body) {
        RequestValues.requireObject(body, MOVIE_PATCH_FIELDS);
        requirePresent(body, Set.of("version"));
        requireAnyChange(body);
        return new MoviePatch(version(body.get("version")),
                body.containsKey("title") ? title(body.get("title")) : null,
                body.containsKey("runtimeMinutes") ? runtime(body.get("runtimeMinutes")) : null,
                body.containsKey("ageRating") ? ageRating(body.get("ageRating")) : null);
    }

    public static ScreeningCreate screeningCreate(Map<String, Object> body) {
        RequestValues.requireObject(body, SCREENING_CREATE_FIELDS);
        requirePresent(body, SCREENING_CREATE_FIELDS);
        return new ScreeningCreate(movieId(body.get("movieId")), theaterId(body.get("theaterId")),
                startsAt(body.get("startsAt")), price(body.get("basePrice")));
    }

    public static ScreeningPatch screeningPatch(Map<String, Object> body) {
        RequestValues.requireObject(body, SCREENING_PATCH_FIELDS);
        requirePresent(body, Set.of("version"));
        requireAnyChange(body);
        return new ScreeningPatch(version(body.get("version")),
                body.containsKey("theaterId") ? theaterId(body.get("theaterId")) : null,
                body.containsKey("startsAt") ? startsAt(body.get("startsAt")) : null,
                body.containsKey("basePrice") ? price(body.get("basePrice")) : null);
    }

    static String title(Object raw) {
        if (raw instanceof String title && !title.isBlank()) {
            int length = title.codePointCount(0, title.length());
            if (length >= 1 && length <= TITLE_MAX) {
                return title;
            }
        }
        throw RequestValues.badRequest("INVALID_TITLE", "제목은 1~200자여야 합니다.");
    }

    static int runtime(Object raw) {
        return (int) RequestValues.jsonInteger(raw, ScreeningSchedule.RUNTIME_MIN, ScreeningSchedule.RUNTIME_MAX,
                "INVALID_RUNTIME", "상영시간은 1~600분 정수여야 합니다.");
    }

    static AgeRating ageRating(Object raw) {
        if (raw instanceof String value) {
            for (AgeRating rating : AgeRating.values()) {
                if (rating.name().equals(value)) {
                    return rating;
                }
            }
        }
        throw RequestValues.badRequest("INVALID_AGE_RATING", "관람등급은 ALL, AGE_12, AGE_15, ADULT 중 하나여야 합니다.");
    }

    static long version(Object raw) {
        return RequestValues.jsonInteger(raw, 0, Long.MAX_VALUE, "INVALID_VERSION", "version은 0 이상의 정수여야 합니다.");
    }

    static long movieId(Object raw) {
        return RequestValues.jsonInteger(raw, 1, Long.MAX_VALUE, "INVALID_MOVIE_ID", "유효하지 않은 영화 ID입니다.");
    }

    static long theaterId(Object raw) {
        return RequestValues.jsonInteger(raw, 1, Long.MAX_VALUE, "INVALID_THEATER_ID", "유효하지 않은 상영관 ID입니다.");
    }

    static Instant startsAt(Object raw) {
        return RequestValues.utcInstant(raw, "INVALID_STARTS_AT",
                "startsAt은 UTC ISO-8601 형식(예: 2030-01-01T10:00:00Z)이어야 합니다.");
    }

    static long price(Object raw) {
        return RequestValues.jsonInteger(raw, ScreeningSchedule.PRICE_MIN, ScreeningSchedule.PRICE_MAX,
                "INVALID_PRICE", "가격은 0~1,000,000원 정수여야 합니다.");
    }

    private static void requirePresent(Map<String, Object> body, Set<String> required) {
        for (String field : required) {
            if (!body.containsKey(field)) {
                throw RequestValues.badRequest("INVALID_REQUEST", "필수 입력 항목이 없습니다.");
            }
        }
    }

    private static void requireAnyChange(Map<String, Object> body) {
        if (body.size() < 2) {
            throw RequestValues.badRequest("INVALID_REQUEST", "변경할 항목이 없습니다.");
        }
    }
}
