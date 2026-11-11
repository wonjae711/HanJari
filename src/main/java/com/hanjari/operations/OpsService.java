package com.hanjari.operations;

import com.hanjari.common.ApiException;
import com.hanjari.screening.ScreeningSchedule;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 최소 운영 API의 변경 작업.
 *
 * <ul>
 *   <li>READ COMMITTED에서 잠금 읽기로 최신 커밋을 보고 판정한다. 시각 판정은 잠금을 얻은 뒤 DB UTC_TIMESTAMP(6)로 한다.</li>
 *   <li>회차 작업의 잠금 순서: 상영관(ID 오름차순) → 회차. 회원 잠금은 얻지 않는다. 같은 상영관의 회차 생성·이동·시간 변경은
 *       상영관 행 잠금으로 직렬화되므로 동시에 들어온 중첩 등록 중 하나만 성공한다.</li>
 *   <li>판매 잠금(sales_locked_at)된 회차는 시간·가격·상영관 변경과 삭제를 거부한다(409 SALES_LOCKED).</li>
 *   <li>수정은 version이 현재 값과 같아야 하며(아니면 409 VERSION_CONFLICT), 성공하면 version을 1 올린다.</li>
 *   <li>영화 수정은 회차를 건드리지 않는다. 기존 회차의 종료 시각은 생성·시작 변경 시점에 계산해 저장한 값 그대로다.</li>
 * </ul>
 */
@Service
public class OpsService {

    private static final int THEATER_RESOLVE_ATTEMPTS = 3;

    private final JdbcTemplate jdbcTemplate;

    public OpsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record OpsMovie(long id, String title, int runtimeMinutes, String ageRating, long version) {
    }

    public record OpsScreening(long id, long movieId, long theaterId, String startsAt, String endsAt, long basePrice,
            boolean salesLocked, long version) {
    }

    private record ScreeningRow(long id, long movieId, long theaterId, Instant startsAt, Instant endsAt, long basePrice,
            boolean salesLocked, long version) {
        OpsScreening toResponse() {
            return new OpsScreening(id, movieId, theaterId, startsAt.toString(), endsAt.toString(), basePrice,
                    salesLocked, version);
        }
    }

    // 영화 ----------------------------------------------------------------------------------------------

    @Transactional
    public OpsMovie createMovie(OpsRequestPolicy.MovieCreate request) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO movies (title, runtime_minutes, age_rating, version) VALUES (?, ?, ?, 0)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, request.title());
            statement.setInt(2, request.runtimeMinutes());
            statement.setString(3, request.ageRating().name());
            return statement;
        }, keys);
        return new OpsMovie(keys.getKey().longValue(), request.title(), request.runtimeMinutes(),
                request.ageRating().name(), 0);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OpsMovie updateMovie(long movieId, OpsRequestPolicy.MoviePatch patch) {
        List<OpsMovie> rows = jdbcTemplate.query(
                "SELECT id, title, runtime_minutes, age_rating, version FROM movies WHERE id = ? FOR UPDATE",
                (rs, n) -> new OpsMovie(rs.getLong("id"), rs.getString("title"), rs.getInt("runtime_minutes"),
                        rs.getString("age_rating"), rs.getLong("version")),
                movieId);
        if (rows.isEmpty()) {
            throw notFound("MOVIE_NOT_FOUND", "영화를 찾을 수 없습니다.");
        }
        OpsMovie current = rows.get(0);
        if (current.version() != patch.version()) {
            throw versionConflict();
        }
        OpsMovie updated = new OpsMovie(movieId,
                patch.title() != null ? patch.title() : current.title(),
                patch.runtimeMinutes() != null ? patch.runtimeMinutes() : current.runtimeMinutes(),
                patch.ageRating() != null ? patch.ageRating().name() : current.ageRating(),
                current.version() + 1);
        // 회차의 종료 시각은 재계산하지 않는다(판매 잠금 여부와 무관).
        jdbcTemplate.update("UPDATE movies SET title = ?, runtime_minutes = ?, age_rating = ?, version = ? "
                        + "WHERE id = ? AND version = ?",
                updated.title(), updated.runtimeMinutes(), updated.ageRating(), updated.version(),
                movieId, current.version());
        return updated;
    }

    // 회차 ----------------------------------------------------------------------------------------------

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OpsScreening createScreening(OpsRequestPolicy.ScreeningCreate request) {
        lockTheaters(List.of(request.theaterId()));
        int runtime = runtimeOf(request.movieId());
        Instant now = dbNow();
        requireFuture(request.startsAt(), now);
        Instant endsAt = ScreeningSchedule.endsAt(request.startsAt(), runtime);
        requireStorable(request.startsAt(), endsAt);
        requireNoOverlap(request.theaterId(), request.startsAt(), endsAt, null);

        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO screenings (movie_id, theater_id, starts_at, ends_at, base_price, version) "
                            + "VALUES (?, ?, ?, ?, ?, 0)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, request.movieId());
            statement.setLong(2, request.theaterId());
            statement.setObject(3, ScreeningSchedule.toDb(request.startsAt()));
            statement.setObject(4, ScreeningSchedule.toDb(endsAt));
            statement.setLong(5, request.basePrice());
            return statement;
        }, keys);
        long id = keys.getKey().longValue();
        createInventory(id, request.theaterId());
        return new ScreeningRow(id, request.movieId(), request.theaterId(), request.startsAt(), endsAt,
                request.basePrice(), false, 0).toResponse();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OpsScreening updateScreening(long screeningId, OpsRequestPolicy.ScreeningPatch patch) {
        ScreeningRow current = lockScreeningWithTheaters(screeningId, patch.theaterId());
        if (current.salesLocked()) {
            throw salesLocked();
        }
        if (current.version() != patch.version()) {
            throw versionConflict();
        }
        // 필요한 잠금(상영관 → 회차 → 영화 공유 잠금)을 모두 얻은 뒤에 DB 시각을 읽는다. 영화 잠금 대기 중 시작 시각이
        // 지나간 경우에도 대기 이후의 now로 판정해야 하기 때문이다.
        Integer runtime = patch.startsAt() != null ? runtimeOf(current.movieId()) : null;
        Instant now = dbNow();
        if (patch.startsAt() != null) {
            requireFuture(patch.startsAt(), now);
        } else if (!current.startsAt().isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "SCREENING_STARTED", "이미 시작한 회차는 변경할 수 없습니다.");
        }
        long theaterId = patch.theaterId() != null ? patch.theaterId() : current.theaterId();
        Instant startsAt = patch.startsAt() != null ? patch.startsAt() : current.startsAt();
        Instant endsAt = runtime != null ? ScreeningSchedule.endsAt(startsAt, runtime) : current.endsAt();
        requireStorable(startsAt, endsAt);
        long basePrice = patch.basePrice() != null ? patch.basePrice() : current.basePrice();
        boolean theaterChanged = theaterId != current.theaterId();
        if (theaterChanged || !startsAt.equals(current.startsAt()) || !endsAt.equals(current.endsAt())) {
            requireNoOverlap(theaterId, startsAt, endsAt, screeningId);
        }
        if (theaterChanged) {
            // inventory는 (회차, 상영관) 복합 FK로 묶여 있다. 판매 잠금 전이라 확보·예매가 없으므로 새 상영관 좌석으로 다시 만든다.
            jdbcTemplate.update("DELETE FROM screening_seats WHERE screening_id = ?", screeningId);
        }
        jdbcTemplate.update("UPDATE screenings SET theater_id = ?, starts_at = ?, ends_at = ?, base_price = ?, "
                        + "version = version + 1 WHERE id = ? AND version = ? AND sales_locked_at IS NULL",
                theaterId, ScreeningSchedule.toDb(startsAt), ScreeningSchedule.toDb(endsAt), basePrice,
                screeningId, current.version());
        if (theaterChanged) {
            createInventory(screeningId, theaterId);
        }
        return new ScreeningRow(screeningId, current.movieId(), theaterId, startsAt, endsAt, basePrice, false,
                current.version() + 1).toResponse();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteScreening(long screeningId) {
        ScreeningRow current = lockScreeningWithTheaters(screeningId, null);
        if (current.salesLocked()) {
            throw salesLocked();
        }
        jdbcTemplate.update("DELETE FROM screening_seats WHERE screening_id = ?", screeningId);
        jdbcTemplate.update("DELETE FROM screenings WHERE id = ? AND sales_locked_at IS NULL", screeningId);
    }

    // 내부 ----------------------------------------------------------------------------------------------

    /**
     * 상영관(현재·이동 대상, ID 오름차순) → 회차 순서로 잠근다. 잠그는 사이 다른 작업이 상영관을 바꿨다면 다시 시도한다.
     */
    private ScreeningRow lockScreeningWithTheaters(long screeningId, Long targetTheaterId) {
        for (int attempt = 0; attempt < THEATER_RESOLVE_ATTEMPTS; attempt++) {
            List<Long> theater = jdbcTemplate.queryForList(
                    "SELECT theater_id FROM screenings WHERE id = ?", Long.class, screeningId);
            if (theater.isEmpty()) {
                throw screeningNotFound();
            }
            TreeSet<Long> toLock = new TreeSet<>();
            toLock.add(theater.get(0));
            if (targetTheaterId != null) {
                toLock.add(targetTheaterId);
            }
            lockTheaters(List.copyOf(toLock));
            List<ScreeningRow> rows = jdbcTemplate.query(
                    "SELECT id, movie_id, theater_id, starts_at, ends_at, base_price, sales_locked_at, version "
                            + "FROM screenings WHERE id = ? FOR UPDATE",
                    (rs, n) -> new ScreeningRow(rs.getLong("id"), rs.getLong("movie_id"), rs.getLong("theater_id"),
                            ScreeningSchedule.fromDb(rs.getObject("starts_at", LocalDateTime.class)),
                            ScreeningSchedule.fromDb(rs.getObject("ends_at", LocalDateTime.class)),
                            rs.getLong("base_price"), rs.getObject("sales_locked_at") != null, rs.getLong("version")),
                    screeningId);
            if (rows.isEmpty()) {
                throw screeningNotFound();
            }
            if (Objects.equals(rows.get(0).theaterId(), theater.get(0))) {
                return rows.get(0);
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "다른 변경과 충돌했습니다. 최신 정보로 다시 시도하세요.");
    }

    /** 오름차순 ID 목록의 상영관 행을 배타 잠금한다. 없는 상영관이면 404. */
    private void lockTheaters(List<Long> theaterIds) {
        for (long theaterId : theaterIds) {
            List<Long> found = jdbcTemplate.queryForList(
                    "SELECT id FROM theaters WHERE id = ? FOR UPDATE", Long.class, theaterId);
            if (found.isEmpty()) {
                throw notFound("THEATER_NOT_FOUND", "상영관을 찾을 수 없습니다.");
            }
        }
    }

    private int runtimeOf(long movieId) {
        List<Integer> runtime = jdbcTemplate.queryForList(
                "SELECT runtime_minutes FROM movies WHERE id = ? FOR SHARE", Integer.class, movieId);
        if (runtime.isEmpty()) {
            throw notFound("MOVIE_NOT_FOUND", "영화를 찾을 수 없습니다.");
        }
        return runtime.get(0);
    }

    private Instant dbNow() {
        return ScreeningSchedule.fromDb(jdbcTemplate.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class));
    }

    private static void requireFuture(Instant startsAt, Instant now) {
        if (!startsAt.isAfter(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STARTS_AT_NOT_FUTURE", "시작 시각은 현재 이후여야 합니다.");
        }
    }

    /** 시작·종료·점유 종료가 DATETIME(6) 저장 범위를 넘으면 DB 예외(500) 대신 입력 오류 400으로 거부한다. */
    private static void requireStorable(Instant startsAt, Instant endsAt) {
        if (!ScreeningSchedule.fitsStorage(startsAt, endsAt)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STARTS_AT_OUT_OF_RANGE",
                    "종료 시각과 준비 시간이 저장 가능한 범위(9999-12-31T23:59:59.999999Z)를 넘습니다.");
        }
    }

    /** 같은 상영관의 [시작, 종료+10분) 구간과 겹치는 다른 회차가 있으면 409. 호출 전에 상영관 행을 잠가야 한다. */
    private void requireNoOverlap(long theaterId, Instant startsAt, Instant endsAt, Long excludeScreeningId) {
        Integer overlapping = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM screenings WHERE theater_id = ? AND id <> ? "
                        + "AND starts_at < ? AND ? < DATE_ADD(ends_at, INTERVAL ? SECOND)",
                Integer.class, theaterId, excludeScreeningId == null ? 0L : excludeScreeningId,
                ScreeningSchedule.toDb(ScreeningSchedule.occupiedUntil(endsAt)), ScreeningSchedule.toDb(startsAt),
                ScreeningSchedule.PREPARATION.toSeconds());
        if (overlapping != null && overlapping > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "SCREENING_OVERLAP",
                    "같은 상영관의 다른 회차(준비 시간 10분 포함)와 시간이 겹칩니다.");
        }
    }

    private void createInventory(long screeningId, long theaterId) {
        jdbcTemplate.update("INSERT INTO screening_seats (screening_id, theater_id, seat_id, state) "
                + "SELECT ?, theater_id, id, 'AVAILABLE' FROM seats WHERE theater_id = ?", screeningId, theaterId);
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private static ApiException screeningNotFound() {
        return notFound("SCREENING_NOT_FOUND", "회차를 찾을 수 없습니다.");
    }

    private static ApiException salesLocked() {
        return new ApiException(HttpStatus.CONFLICT, "SALES_LOCKED", "판매가 시작된 회차는 변경하거나 삭제할 수 없습니다.");
    }

    private static ApiException versionConflict() {
        return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "다른 수정이 먼저 반영되었습니다. 최신 version으로 다시 시도하세요.");
    }
}
