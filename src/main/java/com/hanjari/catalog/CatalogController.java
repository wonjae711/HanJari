package com.hanjari.catalog;

import com.hanjari.common.RequestValues;
import java.time.LocalDate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비회원 공개 조회: 영화 목록, 한국 날짜별 회차, 회차 좌석. 기존 영화 상세(GET /movies/{id})는 MovieController가 유지한다.
 * 쿼리 값은 문자열로 받아 직접 검증한다(부호·선행 0·중복 파라미터 거부).
 */
@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class CatalogController {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_PAGE = 1_000_000;

    private final CatalogQueryService queryService;

    public CatalogController(CatalogQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/movies")
    public CatalogResponses.MoviePage movies(@RequestParam(value = "page", required = false) String page,
            @RequestParam(value = "size", required = false) String size) {
        int pageValue = RequestValues.boundedInt(page, 0, 0, MAX_PAGE, "INVALID_PAGE",
                "page는 0 이상의 정수여야 합니다.");
        int sizeValue = RequestValues.boundedInt(size, DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE, "INVALID_SIZE",
                "size는 1~100 사이의 정수여야 합니다.");
        return queryService.movies(pageValue, sizeValue);
    }

    @GetMapping("/screenings")
    public CatalogResponses.ScreeningList screenings(@RequestParam(value = "movieId", required = false) String movieId,
            @RequestParam(value = "date", required = false) String date) {
        long movie = RequestValues.positiveId(movieId, "INVALID_MOVIE_ID", "유효하지 않은 영화 ID입니다.");
        LocalDate day = RequestValues.date(date, "INVALID_DATE", "date는 YYYY-MM-DD 형식의 날짜여야 합니다.");
        return queryService.screenings(movie, day);
    }

    @GetMapping("/screenings/{screeningId}/seats")
    public CatalogResponses.SeatMap seats(@PathVariable("screeningId") String screeningId) {
        return queryService.seats(RequestValues.positiveId(screeningId, "INVALID_SCREENING_ID",
                "유효하지 않은 회차 ID입니다."));
    }
}
