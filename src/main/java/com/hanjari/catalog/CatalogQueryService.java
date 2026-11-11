package com.hanjari.catalog;

import com.hanjari.common.ApiException;
import com.hanjari.common.DatabaseTime;
import com.hanjari.movie.MovieDetailResponse;
import com.hanjari.screening.ScreeningSchedule;
import java.time.Instant;
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
    private final DatabaseTime databaseTime;

    public CatalogQueryService(JdbcTemplate jdbcTemplate, DatabaseTime databaseTime) {
        this.jdbcTemplate = jdbcTemplate;
        this.databaseTime = databaseTime;
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
        LocalDateTime now = ScreeningSchedule.toDb(databaseTime.now());
        // 빈 좌석 = AVAILABLE이거나, 소유 확보가 이미 만료(작업자 반영 전 포함)된 HELD 좌석.
        List<CatalogResponses.ScreeningSummary> items = jdbcTemplate.query(
                "SELECT s.id, s.movie_id, s.theater_id, t.name, s.starts_at, s.ends_at, s.base_price, "
                        + "(s.starts_at > ? AND EXISTS (SELECT 1 FROM screening_seats ss "
                        + "  LEFT JOIN holds h ON h.id = ss.hold_id "
                        + "  WHERE ss.screening_id = s.id AND (ss.state = 'AVAILABLE' OR (ss.state = 'HELD' "
                        + "    AND (h.status <> 'ACTIVE' OR h.expires_at <= ?))))) AS bookable "
                        + "FROM screenings s JOIN theaters t ON t.id = s.theater_id "
                        + "WHERE s.movie_id = ? AND s.starts_at >= ? AND s.starts_at < ? "
                        + "ORDER BY s.starts_at, s.id",
                (rs, n) -> new CatalogResponses.ScreeningSummary(rs.getLong("id"), rs.getLong("movie_id"),
                        rs.getLong("theater_id"), rs.getString("name"),
                        ScreeningSchedule.fromDb(rs.getObject("starts_at", LocalDateTime.class)).toString(),
                        ScreeningSchedule.fromDb(rs.getObject("ends_at", LocalDateTime.class)).toString(),
                        rs.getLong("base_price"), rs.getBoolean("bookable")),
                now, now, movieId, ScreeningSchedule.toDb(day.from()), ScreeningSchedule.toDb(day.to()));
        return new CatalogResponses.ScreeningList(items);
    }

    /**
     * 좌석 위치·상태와 요청자 본인 확보 여부만 반환한다. 확보·회원 식별자는 응답에 넣지 않고, 본인 여부는 DB 안에서
     * 요청자 ID와 비교한 불리언으로만 읽는다.
     *
     * <p>HELD는 소유 확보가 ACTIVE이고 기준 시각이 만료 시각 전일 때만이다. 만료 작업자가 아직 반영하지 않은 만료 확보 좌석은
     * AVAILABLE로 보인다(조회는 상태를 바꾸거나 확보를 연장하지 않는다).
     *
     * @param viewerMemberId 로그인한 요청자 회원 ID, 비회원이면 null
     */
    @Transactional(readOnly = true)
    public CatalogResponses.SeatMap seats(long screeningId, Long viewerMemberId) {
        Integer screenings = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM screenings WHERE id = ?", Integer.class, screeningId);
        if (screenings == null || screenings == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SCREENING_NOT_FOUND", "회차를 찾을 수 없습니다.");
        }
        Instant now = databaseTime.now();
        LocalDateTime dbNow = ScreeningSchedule.toDb(now);
        List<CatalogResponses.SeatView> seats = jdbcTemplate.query(
                "SELECT ss.seat_id, se.row_label, se.seat_number, "
                        + "CASE WHEN ss.state = 'BOOKED' THEN 'BOOKED' "
                        + "     WHEN ss.state = 'HELD' AND h.status = 'ACTIVE' AND h.expires_at > ? THEN 'HELD' "
                        + "     ELSE 'AVAILABLE' END AS status, "
                        + "COALESCE(ss.state = 'HELD' AND h.status = 'ACTIVE' AND h.expires_at > ? "
                        + "         AND h.member_id = ?, FALSE) AS mine "
                        + "FROM screening_seats ss JOIN seats se ON se.id = ss.seat_id "
                        + "LEFT JOIN holds h ON h.id = ss.hold_id "
                        + "WHERE ss.screening_id = ? "
                        + "ORDER BY CHAR_LENGTH(se.row_label), se.row_label, se.seat_number, se.id",
                (rs, n) -> new CatalogResponses.SeatView(rs.getLong("seat_id"), rs.getString("row_label"),
                        rs.getInt("seat_number"), rs.getString("status"), rs.getBoolean("mine")),
                dbNow, dbNow, viewerMemberId, screeningId);
        String expiresAt = null;
        if (viewerMemberId != null) {
            List<LocalDateTime> own = jdbcTemplate.queryForList("SELECT expires_at FROM holds "
                            + "WHERE member_id = ? AND screening_id = ? AND status = 'ACTIVE' AND expires_at > ? "
                            + "ORDER BY id DESC LIMIT 1",
                    LocalDateTime.class, viewerMemberId, screeningId, dbNow);
            expiresAt = own.isEmpty() ? null : ScreeningSchedule.fromDb(own.get(0)).toString();
        }
        return new CatalogResponses.SeatMap(screeningId, now.toString(), seats, expiresAt);
    }
}
