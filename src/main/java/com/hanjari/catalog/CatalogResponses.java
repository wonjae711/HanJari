package com.hanjari.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hanjari.movie.MovieDetailResponse;
import java.util.List;

/**
 * 공개 조회 응답. 회원·확보·예매 식별자나 로그인 ID 같은 타인 신원 정보는 필드로 두지 않는다.
 * 시각은 UTC ISO-8601 문자열이다.
 */
public final class CatalogResponses {

    private CatalogResponses() {
    }

    /** 영화 목록. 항목은 기존 영화 상세와 같은 네 필드다. */
    public record MoviePage(List<MovieDetailResponse> items, int page, int size, boolean hasNext) {
    }

    public record ScreeningSummary(long id, long movieId, long theaterId, String theaterName, String startsAt,
            String endsAt, long basePrice, boolean bookable) {
    }

    public record ScreeningList(List<ScreeningSummary> items) {
    }

    /**
     * 좌석 하나. status는 AVAILABLE/HELD/BOOKED, mine은 로그인한 요청자 본인의 유효 확보 좌석인지다(비회원은 항상 false).
     * 만료 시각이 지난 확보 좌석은 작업자 반영 전이라도 AVAILABLE로 보인다(HJ-006).
     */
    public record SeatView(long seatId, String row, int number, String status, boolean mine) {
    }

    /** 본인 확보 만료 시각(expiresAt)은 이 회차에 본인의 유효 확보가 있을 때만 응답에 넣는다(HJ-006). */
    public record SeatMap(long screeningId, String serverNow, List<SeatView> seats,
            @JsonInclude(JsonInclude.Include.NON_NULL) String expiresAt) {
    }
}
