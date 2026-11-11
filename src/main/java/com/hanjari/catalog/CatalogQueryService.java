package com.hanjari.catalog;

import com.hanjari.common.ApiException;
import com.hanjari.movie.MovieDetailResponse;
import com.hanjari.screening.ScreeningSchedule;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 비회원 공개 조회. 읽기 전용 트랜잭션 하나의 스냅샷에서 응답을 만든다. */
@Service
public class CatalogQueryService {

    private final JdbcTemplate jdbcTemplate;

    public CatalogQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** id 오름차순. 다음 페이지 여부는 size+1건 조회로 판단한다. */
    @Transactional(readOnly = true)
    public CatalogResponses.MoviePage movies(int page, int size) {
        List<MovieDetailResponse> rows = jdbcTemplate.query(
                "SELECT id, title, runtime_minutes, age_rating FROM movies ORDER BY id LIMIT ? OFFSET ?",
                (rs, n) -> new MovieDetailResponse(rs.getLong("id"), rs.getString("title"),
                        rs.getInt("runtime_minutes"), rs.getString("age_rating")),
                size + 1, (long) page * size);
        boolean hasNext = rows.size() > size;
        return new CatalogResponses.MoviePage(hasNext ? rows.subList(0, size) : rows, page, size, hasNext);
    }

    /**
     * 한국 날짜에 시작하는 회차를 startsAt, id 순으로 반환한다. 예매 가능 = 아직 시작 전(DB 시각 기준)이고 빈 좌석이 있음.
     */
    @Transactional(readOnly = true)
    public CatalogResponses.ScreeningList screenings(long movieId, LocalDate koreanDate) {
        Integer movies = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM movies WHERE id = ?", Integer.class, movieId);
        if (movies == null || movies == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MOVIE_NOT_FOUND", "영화를 찾을 수 없습니다.");
        }
        ScreeningSchedule.UtcRange day = ScreeningSchedule.koreanDay(koreanDate);
        List<CatalogResponses.ScreeningSummary> items = jdbcTemplate.query(
                "SELECT s.id, s.movie_id, s.theater_id, t.name, s.starts_at, s.ends_at, s.base_price, "
                        + "(s.starts_at > UTC_TIMESTAMP(6) AND EXISTS (SELECT 1 FROM screening_seats ss "
                        + "  WHERE ss.screening_id = s.id AND ss.state = 'AVAILABLE')) AS bookable "
                        + "FROM screenings s JOIN theaters t ON t.id = s.theater_id "
                        + "WHERE s.movie_id = ? AND s.starts_at >= ? AND s.starts_at < ? "
                        + "ORDER BY s.starts_at, s.id",
                (rs, n) -> new CatalogResponses.ScreeningSummary(rs.getLong("id"), rs.getLong("movie_id"),
                        rs.getLong("theater_id"), rs.getString("name"),
                        ScreeningSchedule.fromDb(rs.getObject("starts_at", LocalDateTime.class)).toString(),
                        ScreeningSchedule.fromDb(rs.getObject("ends_at", LocalDateTime.class)).toString(),
                        rs.getLong("base_price"), rs.getBoolean("bookable")),
                movieId, ScreeningSchedule.toDb(day.from()), ScreeningSchedule.toDb(day.to()));
        return new CatalogResponses.ScreeningList(items);
    }

    /** 좌석 위치·상태만 반환한다. 다른 회원의 확보·예매 식별자는 조회하지도 않는다. */
    @Transactional(readOnly = true)
    public CatalogResponses.SeatMap seats(long screeningId) {
        Integer screenings = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM screenings WHERE id = ?", Integer.class, screeningId);
        if (screenings == null || screenings == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SCREENING_NOT_FOUND", "회차를 찾을 수 없습니다.");
        }
        LocalDateTime now = jdbcTemplate.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
        List<CatalogResponses.SeatView> seats = jdbcTemplate.query(
                "SELECT ss.seat_id, se.row_label, se.seat_number, ss.state "
                        + "FROM screening_seats ss JOIN seats se ON se.id = ss.seat_id "
                        + "WHERE ss.screening_id = ? "
                        + "ORDER BY CHAR_LENGTH(se.row_label), se.row_label, se.seat_number, se.id",
                (rs, n) -> new CatalogResponses.SeatView(rs.getLong("seat_id"), rs.getString("row_label"),
                        rs.getInt("seat_number"), rs.getString("state"), false),
                screeningId);
        return new CatalogResponses.SeatMap(screeningId, ScreeningSchedule.fromDb(now).toString(), seats);
    }
}
