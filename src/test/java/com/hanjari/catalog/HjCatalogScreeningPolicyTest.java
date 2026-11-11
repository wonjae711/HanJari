package com.hanjari.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.hanjari.common.ApiException;
import com.hanjari.common.RequestValues;
import com.hanjari.movie.AgeRating;
import com.hanjari.operations.OpsRequestPolicy;
import com.hanjari.screening.ScreeningSchedule;
import java.lang.reflect.RecordComponent;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * HJ-004 공개 조회·회차·운영 입력 정책의 DB 없는 단위 검증. 실제 MySQL·HTTP 동작은 {@code HjCatalogOperationsIT}에서 검증한다.
 */
class HjCatalogScreeningPolicyTest {

    @Test
    void pageAndSizeAcceptOnlyPlainDecimalsWithinBounds() {
        assertThat(RequestValues.boundedInt(null, 0, 0, 1_000_000, "INVALID_PAGE", "m")).isZero();
        assertThat(RequestValues.boundedInt(null, 20, 1, 100, "INVALID_SIZE", "m")).isEqualTo(20);
        assertThat(RequestValues.boundedInt("0", 20, 0, 100, "INVALID_PAGE", "m")).isZero();
        assertThat(RequestValues.boundedInt("1", 20, 1, 100, "INVALID_SIZE", "m")).isEqualTo(1);
        assertThat(RequestValues.boundedInt("100", 20, 1, 100, "INVALID_SIZE", "m")).isEqualTo(100);

        for (String invalid : new String[] {"", " 1", "1 ", "+1", "-1", "01", "00", "1.0", "1e2", "abc", "1,2", "0", "101",
                "9999999999", "٣"}) {
            ApiException error = catchThrowableOfType(ApiException.class,
                    () -> RequestValues.boundedInt(invalid, 20, 1, 100, "INVALID_SIZE", "m"));
            assertThat(error).as("size '%s'", invalid).isNotNull();
            assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(error.getCode()).isEqualTo("INVALID_SIZE");
            assertThat(error.getMessage()).doesNotContain(invalid.isBlank() ? "\u0000" : invalid);
        }
    }

    @Test
    void positiveIdsRejectSignsLeadingZerosZeroAndOverflow() {
        assertThat(RequestValues.positiveId("1", "INVALID_SCREENING_ID", "m")).isEqualTo(1L);
        assertThat(RequestValues.positiveId("9223372036854775807", "INVALID_SCREENING_ID", "m")).isEqualTo(Long.MAX_VALUE);
        for (String invalid : new String[] {null, "", "0", "-1", "+1", "01", "1.0", "abc", " 1",
                "9223372036854775808", "99999999999999999999", "１"}) {
            assertThatThrownBy(() -> RequestValues.positiveId(invalid, "INVALID_SCREENING_ID", "m"))
                    .as("id '%s'", invalid)
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getCode()).isEqualTo("INVALID_SCREENING_ID"));
        }
    }

    @Test
    void koreanDateMapsToUtcHalfOpenRangeAndRejectsImpossibleDates() {
        LocalDate date = RequestValues.date("2031-03-01", "INVALID_DATE", "m");
        ScreeningSchedule.UtcRange range = ScreeningSchedule.koreanDay(date);
        // KST = UTC+9(일광절약 없음): 03-01 00:00 KST = 02-28 15:00Z, 다음 날 00:00 KST = 03-01 15:00Z.
        assertThat(range.from()).isEqualTo(Instant.parse("2031-02-28T15:00:00Z"));
        assertThat(range.to()).isEqualTo(Instant.parse("2031-03-01T15:00:00Z"));
        // 윤일은 존재하는 날짜다.
        assertThat(RequestValues.date("2032-02-29", "INVALID_DATE", "m")).isEqualTo(LocalDate.of(2032, 2, 29));

        for (String invalid : new String[] {null, "", "2031-02-29", "2031-02-30", "2031-13-01", "2031-3-1", "20310301",
                "2031-03-01T00:00", "2031/03/01", " 2031-03-01", "+2031-03-01"}) {
            assertThatThrownBy(() -> RequestValues.date(invalid, "INVALID_DATE", "m"))
                    .as("date '%s'", invalid)
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_DATE"));
        }
    }

    @Test
    void startsAtRequiresUtcIsoWithAtMostMicrosecondPrecision() {
        assertThat(RequestValues.utcInstant("2031-03-01T01:00:00Z", "INVALID_STARTS_AT", "m"))
                .isEqualTo(Instant.parse("2031-03-01T01:00:00Z"));
        assertThat(RequestValues.utcInstant("2031-03-01T01:00:00.123456Z", "INVALID_STARTS_AT", "m"))
                .isEqualTo(Instant.parse("2031-03-01T01:00:00.123456Z"));
        for (Object invalid : new Object[] {null, "", 1_900_000_000L, "2031-03-01T10:00:00+09:00", "2031-03-01T01:00:00",
                "2031-03-01T01:00Z", "2031-03-01T01:00:00.1234567Z", "2031-03-01 01:00:00Z", "2031-02-30T01:00:00Z",
                "2031-03-01T24:00:00Z", "2031-03-01t01:00:00z"}) {
            assertThatThrownBy(() -> RequestValues.utcInstant(invalid, "INVALID_STARTS_AT", "m"))
                    .as("startsAt %s", invalid)
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getCode()).isEqualTo("INVALID_STARTS_AT"));
        }
    }

    @Test
    void occupancyIncludesTenMinutePreparationAsHalfOpenInterval() {
        Instant start = Instant.parse("2031-03-01T01:00:00Z");
        Instant end = ScreeningSchedule.endsAt(start, 120); // 03:00Z, 점유는 03:10Z까지(배타)
        assertThat(end).isEqualTo(Instant.parse("2031-03-01T03:00:00Z"));
        assertThat(ScreeningSchedule.occupiedUntil(end)).isEqualTo(Instant.parse("2031-03-01T03:10:00Z"));

        // 뒤 회차: 정확히 종료+10분 시작은 허용, 1µs 빠르면 중첩.
        Instant exactly = Instant.parse("2031-03-01T03:10:00Z");
        assertThat(ScreeningSchedule.overlaps(start, end, exactly, ScreeningSchedule.endsAt(exactly, 90))).isFalse();
        Instant oneMicroEarly = exactly.minusNanos(1_000);
        assertThat(ScreeningSchedule.overlaps(start, end, oneMicroEarly, ScreeningSchedule.endsAt(oneMicroEarly, 90)))
                .isTrue();
        // 앞 회차: 그 종료+10분이 이 회차 시작과 같으면 허용, 1µs 늦으면 중첩.
        Instant before = start.minusSeconds((90 + 10) * 60);
        assertThat(ScreeningSchedule.overlaps(start, end, before, ScreeningSchedule.endsAt(before, 90))).isFalse();
        Instant late = before.plusNanos(1_000);
        assertThat(ScreeningSchedule.overlaps(start, end, late, ScreeningSchedule.endsAt(late, 90))).isTrue();
        // 포함·동일 구간은 대칭적으로 중첩이다.
        Instant inside = start.plusSeconds(1800);
        assertThat(ScreeningSchedule.overlaps(start, end, inside, ScreeningSchedule.endsAt(inside, 10))).isTrue();
        assertThat(ScreeningSchedule.overlaps(inside, ScreeningSchedule.endsAt(inside, 10), start, end)).isTrue();
        assertThat(ScreeningSchedule.overlaps(start, end, start, end)).isTrue();
    }

    @Test
    void endsAtUsesRuntimeWithinOneToSixHundredMinutesAndDbConversionIsUtc() {
        Instant start = Instant.parse("2031-12-31T23:30:00Z");
        assertThat(ScreeningSchedule.endsAt(start, 1)).isEqualTo(Instant.parse("2031-12-31T23:31:00Z"));
        assertThat(ScreeningSchedule.endsAt(start, 600)).isEqualTo(Instant.parse("2032-01-01T09:30:00Z"));
        assertThatThrownBy(() -> ScreeningSchedule.endsAt(start, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScreeningSchedule.endsAt(start, 601)).isInstanceOf(IllegalArgumentException.class);

        Instant micro = Instant.parse("2031-03-01T01:02:03.000004Z");
        assertThat(ScreeningSchedule.toDb(micro).toString()).isEqualTo("2031-03-01T01:02:03.000004");
        assertThat(ScreeningSchedule.fromDb(ScreeningSchedule.toDb(micro))).isEqualTo(micro);
        assertThat(ScreeningSchedule.fromDb(null)).isNull();

        // 저장 상한: 점유 종료(종료 + 10분)가 9999-12-31T23:59:59.999999Z 이하여야 한다.
        Instant lastFit = Instant.parse("9999-12-31T21:49:59.999999Z"); // +120분 +10분 = 23:59:59.999999
        assertThat(ScreeningSchedule.fitsStorage(lastFit, ScreeningSchedule.endsAt(lastFit, 120))).isTrue();
        Instant firstOver = Instant.parse("9999-12-31T21:50:00Z");
        assertThat(ScreeningSchedule.fitsStorage(firstOver, ScreeningSchedule.endsAt(firstOver, 120))).isFalse();
        Instant latestInput = Instant.parse("9999-12-31T23:59:00Z");
        assertThat(ScreeningSchedule.fitsStorage(latestInput, ScreeningSchedule.endsAt(latestInput, 1))).isFalse();
        assertThat(ScreeningSchedule.fitsStorage(start, ScreeningSchedule.endsAt(start, 600))).isTrue();
    }

    @Test
    void movieCreateValidatesTitleRuntimeAndExistingAgeRatingEnum() {
        OpsRequestPolicy.MovieCreate ok = OpsRequestPolicy.movieCreate(
                Map.of("title", "가".repeat(200), "runtimeMinutes", 600, "ageRating", "ADULT"));
        assertThat(ok.runtimeMinutes()).isEqualTo(600);
        assertThat(ok.ageRating()).isEqualTo(AgeRating.ADULT);
        // 보조 평면 문자는 1자로 센다.
        assertThat(OpsRequestPolicy.movieCreate(
                Map.of("title", "😀".repeat(200), "runtimeMinutes", 1, "ageRating", "ALL")).title()).hasSize(400);

        Map<String, Object> base = Map.of("title", "영화", "runtimeMinutes", 100, "ageRating", "ALL");
        Map<Object[], String> invalid = new LinkedHashMap<>();
        invalid.put(new Object[] {"title", ""}, "INVALID_TITLE");
        invalid.put(new Object[] {"title", "   "}, "INVALID_TITLE");
        invalid.put(new Object[] {"title", "가".repeat(201)}, "INVALID_TITLE");
        invalid.put(new Object[] {"title", 123}, "INVALID_TITLE");
        invalid.put(new Object[] {"title", null}, "INVALID_TITLE");
        invalid.put(new Object[] {"runtimeMinutes", 0}, "INVALID_RUNTIME");
        invalid.put(new Object[] {"runtimeMinutes", 601}, "INVALID_RUNTIME");
        invalid.put(new Object[] {"runtimeMinutes", 100.0}, "INVALID_RUNTIME");
        invalid.put(new Object[] {"runtimeMinutes", "100"}, "INVALID_RUNTIME");
        invalid.put(new Object[] {"runtimeMinutes", true}, "INVALID_RUNTIME");
        invalid.put(new Object[] {"runtimeMinutes", new BigInteger("99999999999999999999")}, "INVALID_RUNTIME");
        invalid.put(new Object[] {"ageRating", "AGE_19"}, "INVALID_AGE_RATING");
        invalid.put(new Object[] {"ageRating", "all"}, "INVALID_AGE_RATING");
        invalid.put(new Object[] {"ageRating", 12}, "INVALID_AGE_RATING");
        invalid.put(new Object[] {"version", 0}, "UNSUPPORTED_FIELD");
        invalid.put(new Object[] {"id", 1}, "UNSUPPORTED_FIELD");
        for (Map.Entry<Object[], String> entry : invalid.entrySet()) {
            Map<String, Object> body = new HashMap<>(base);
            body.put((String) entry.getKey()[0], entry.getKey()[1]);
            assertThatThrownBy(() -> OpsRequestPolicy.movieCreate(body))
                    .as("%s=%s", entry.getKey()[0], entry.getKey()[1])
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getCode()).isEqualTo(entry.getValue());
                    });
        }
        Map<String, Object> missing = new HashMap<>(base);
        missing.remove("ageRating");
        assertThatThrownBy(() -> OpsRequestPolicy.movieCreate(missing))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_REQUEST"));
        assertThatThrownBy(() -> OpsRequestPolicy.movieCreate(null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_REQUEST"));
    }

    @Test
    void patchesRequireVersionAndAtLeastOneChangeAndKeepOmittedFieldsNull() {
        OpsRequestPolicy.MoviePatch moviePatch = OpsRequestPolicy.moviePatch(Map.of("version", 3, "runtimeMinutes", 90));
        assertThat(moviePatch.version()).isEqualTo(3);
        assertThat(moviePatch.runtimeMinutes()).isEqualTo(90);
        assertThat(moviePatch.title()).isNull();
        assertThat(moviePatch.ageRating()).isNull();

        OpsRequestPolicy.ScreeningPatch screeningPatch =
                OpsRequestPolicy.screeningPatch(Map.of("version", 0, "basePrice", 0));
        assertThat(screeningPatch.basePrice()).isZero();
        assertThat(screeningPatch.startsAt()).isNull();
        assertThat(screeningPatch.theaterId()).isNull();

        List<Map<String, Object>> invalidRequests = List.of(Map.of("runtimeMinutes", 90), Map.of("version", 1),
                Map.of());
        for (Map<String, Object> body : invalidRequests) {
            assertThatThrownBy(() -> OpsRequestPolicy.moviePatch(body)).as("movie %s", body)
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_REQUEST"));
        }
        assertThatThrownBy(() -> OpsRequestPolicy.screeningPatch(Map.of("basePrice", 1)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_REQUEST"));
        for (Object version : new Object[] {-1, "1", 1.5, null}) {
            Map<String, Object> body = new HashMap<>();
            body.put("version", version);
            body.put("title", "x");
            assertThatThrownBy(() -> OpsRequestPolicy.moviePatch(body)).as("version %s", version)
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_VERSION"));
        }
        // 회차의 영화는 수정 대상이 아니다. 판매 잠금 등 서버 상태 필드도 입력으로 받지 않는다.
        for (String field : new String[] {"movieId", "endsAt", "salesLockedAt", "id"}) {
            assertThatThrownBy(() -> OpsRequestPolicy.screeningPatch(Map.of("version", 0, field, 1)))
                    .as(field)
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("UNSUPPORTED_FIELD"));
        }
    }

    @Test
    void screeningCreateValidatesReferencesStartsAtAndIntegerPriceRange() {
        OpsRequestPolicy.ScreeningCreate ok = OpsRequestPolicy.screeningCreate(Map.of("movieId", 1, "theaterId", 2L,
                "startsAt", "2031-03-01T01:00:00Z", "basePrice", 1_000_000));
        assertThat(ok.movieId()).isEqualTo(1);
        assertThat(ok.theaterId()).isEqualTo(2);
        assertThat(ok.basePrice()).isEqualTo(1_000_000);

        Map<String, Object> base = Map.of("movieId", 1, "theaterId", 2, "startsAt", "2031-03-01T01:00:00Z",
                "basePrice", 12_000);
        Object[][] cases = {
                {"movieId", 0, "INVALID_MOVIE_ID"}, {"movieId", -1, "INVALID_MOVIE_ID"}, {"movieId", "1", "INVALID_MOVIE_ID"},
                {"theaterId", 0, "INVALID_THEATER_ID"}, {"theaterId", 1.0, "INVALID_THEATER_ID"},
                {"startsAt", "2031-03-01T10:00:00+09:00", "INVALID_STARTS_AT"},
                {"basePrice", -1, "INVALID_PRICE"}, {"basePrice", 1_000_001, "INVALID_PRICE"},
                {"basePrice", 1000.5, "INVALID_PRICE"}, {"basePrice", "1000", "INVALID_PRICE"},
                {"price", 1000, "UNSUPPORTED_FIELD"}, {"endsAt", "2031-03-01T03:00:00Z", "UNSUPPORTED_FIELD"},
                {"salesLockedAt", null, "UNSUPPORTED_FIELD"}};
        for (Object[] c : cases) {
            Map<String, Object> body = new HashMap<>(base);
            body.put((String) c[0], c[1]);
            assertThatThrownBy(() -> OpsRequestPolicy.screeningCreate(body)).as("%s=%s", c[0], c[1])
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(c[2]));
        }
        for (String field : base.keySet()) {
            Map<String, Object> body = new HashMap<>(base);
            body.remove(field);
            assertThatThrownBy(() -> OpsRequestPolicy.screeningCreate(body)).as("missing %s", field)
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_REQUEST"));
        }
    }

    @Test
    void publicResponsesCarryNoMemberHoldOrReservationIdentifiers() {
        assertThat(componentNames(CatalogResponses.SeatView.class))
                .containsExactly("seatId", "row", "number", "status", "mine");
        assertThat(componentNames(CatalogResponses.SeatMap.class)).containsExactly("screeningId", "serverNow", "seats");
        assertThat(componentNames(CatalogResponses.ScreeningSummary.class)).containsExactly("id", "movieId",
                "theaterId", "theaterName", "startsAt", "endsAt", "basePrice", "bookable");
        assertThat(componentNames(CatalogResponses.MoviePage.class)).containsExactly("items", "page", "size", "hasNext");
        // 영화 목록 항목은 기존 상세 계약의 네 필드 그대로다(version 등 내부 값 없음).
        assertThat(componentNames(com.hanjari.movie.MovieDetailResponse.class))
                .containsExactly("id", "title", "runtimeMinutes", "ageRating");
        for (Class<?> type : List.of(CatalogResponses.SeatView.class, CatalogResponses.SeatMap.class,
                CatalogResponses.ScreeningSummary.class, CatalogResponses.MoviePage.class,
                CatalogResponses.ScreeningList.class)) {
            assertThat(componentNames(type)).allSatisfy(name -> assertThat(name.toLowerCase())
                    .doesNotContain("member").doesNotContain("login").doesNotContain("hold")
                    .doesNotContain("reservation").doesNotContain("owner").doesNotContain("session"));
        }
    }

    private static List<String> componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents()).map(RecordComponent::getName).toList();
    }
}
