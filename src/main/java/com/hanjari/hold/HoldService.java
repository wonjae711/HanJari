package com.hanjari.hold;

import com.hanjari.common.ApiException;
import com.hanjari.common.DatabaseTime;
import com.hanjari.screening.ScreeningSchedule;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좌석 확보·해제·만료(HJ-006). 정합성 기준은 MySQL이다.
 *
 * <p>잠금 순서(모든 변경 경로 공통): 회원 → 회차(ID 오름차순) → 확보(ID 오름차순) → 좌석. 회차 행의 배타 잠금이 그 회차의 모든 좌석
 * 변경(확보·해제·만료)을 직렬화한다. 회원 행 잠금이 회원당 활성 확보 1건을 보장한다. 판정 시각은 필요한 잠금을 모두 얻은 뒤
 * {@link DatabaseTime}으로 읽는다. 운영 작업(상영관 → 회차)은 회원 잠금을 얻지 않으므로 순환 대기가 생기지 않는다.
 *
 * <p>만료는 별도 작업자({@link HoldExpiryWorker})가 반영하지만, 확보·해제·조회도 만료를 다시 판정한다. 그래서 작업자가 멈춰도
 * 만료 좌석을 다시 확보할 수 있다. 좌석 해제는 항상 "현재 hold_id가 대상 확보와 같을 때만" 수행하고, 상태 전이는
 * "현재 ACTIVE일 때만" 수행한다. 오래된 작업이 다시 실행돼도 새 소유자의 좌석·확보를 바꾸지 못한다.
 *
 * <p>확보 생성·해제는 {@link com.hanjari.idempotency.IdempotencyExecutor}가 연 트랜잭션(READ COMMITTED) 안에서만 실행한다
 * (MANDATORY). 업무 오류는 {@link ApiException}을 던져 확보·판매 잠금·멱등 기록을 함께 롤백한다.
 */
@Service
public class HoldService {

    public static final String HOLD_NOT_FOUND = "HOLD_NOT_FOUND";

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);

    private final JdbcTemplate jdbc;
    private final DatabaseTime time;

    public HoldService(JdbcTemplate jdbc, DatabaseTime time) {
        this.jdbc = jdbc;
        this.time = time;
    }

    /** 본인 확보 조회 응답. 결제·예매 참조는 해당 기능 전이라 항상 null이다. */
    public record HoldView(long holdId, long screeningId, String status, List<Long> seatIds, String expiresAt,
            long totalAmount, String serverNow, Long paymentAttemptId, Long reservationId) {
    }

    private record HoldRow(long id, long memberId, long screeningId, HoldStatus status, Instant expiresAt,
            long totalAmount) {
    }

    private record ScreeningRow(long id, Instant startsAt, long basePrice) {
    }

    private record SeatRow(long seatId, String state, Long holdId) {
    }

    // 확보 생성 ------------------------------------------------------------------------------------------

    /**
     * 한 회차의 서로 다른 1~4석을 한 트랜잭션으로 확보한다. 좌석 하나라도 다른 회차·없음·점유이면 아무것도 바꾸지 않고 실패한다.
     *
     * @return 201 본문 {holdId,status,seatIds,expiresAt,totalAmount,serverNow}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> create(long memberId, HoldRequestPolicy.HoldCreate request) {
        List<Long> seatIds = request.sortedSeatIds();
        lockMember(memberId);
        // 회원 잠금 아래에서는 이 회원의 새 ACTIVE 확보가 생기지 않는다(다른 경로는 ACTIVE를 종결로만 바꾼다).
        List<HoldRow> ownActive = jdbc.query("SELECT id, member_id, screening_id, status, expires_at, total_amount "
                + "FROM holds WHERE member_id = ? AND status = 'ACTIVE' ORDER BY id", HoldService::holdRow, memberId);
        TreeSet<Long> screeningIds = new TreeSet<>();
        screeningIds.add(request.screeningId());
        ownActive.forEach(hold -> screeningIds.add(hold.screeningId()));
        Map<Long, ScreeningRow> screenings = lockScreenings(screeningIds);
        ScreeningRow screening = screenings.get(request.screeningId());
        if (screening == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SCREENING_NOT_FOUND", "회차를 찾을 수 없습니다.");
        }
        Instant now = time.now();

        // 회원당 활성 확보 1건: 아직 유효하면 거부, 만료가 지났으면 먼저 만료 처리한다.
        for (HoldRow hold : lockHolds(ownActive.stream().map(HoldRow::id).toList()).values()) {
            if (hold.status() != HoldStatus.ACTIVE) {
                continue;
            }
            if (!HoldPolicy.isExpired(now, hold.expiresAt())) {
                throw new ApiException(HttpStatus.CONFLICT, "ACTIVE_HOLD_EXISTS",
                        "이미 유효한 좌석 확보가 있습니다. 기존 확보를 해제한 뒤 다시 시도하세요.");
            }
            end(hold, HoldStatus.EXPIRED, now);
        }
        if (HoldPolicy.hasStarted(now, screening.startsAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "SCREENING_NOT_BOOKABLE", "이미 시작한 회차는 확보할 수 없습니다.");
        }

        // 좌석 판정: 회차 잠금이 이 회차의 좌석 변경을 직렬화하므로 여기서 읽은 상태는 커밋까지 바뀌지 않는다.
        // (잠금 순서상 좌석은 확보보다 뒤이므로 여기서는 잠그지 않고 읽는다. 좌석 행은 아래 조건부 UPDATE가 잠근다.)
        List<SeatRow> seats = jdbc.query("SELECT seat_id, state, hold_id FROM screening_seats "
                        + "WHERE screening_id = ? AND seat_id IN (" + placeholders(seatIds.size()) + ") "
                        + "ORDER BY seat_id",
                (rs, n) -> new SeatRow(rs.getLong("seat_id"), rs.getString("state"),
                        (Long) rs.getObject("hold_id", Long.class)),
                args(request.screeningId(), seatIds));
        if (seats.size() != seatIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SEAT_NOT_IN_SCREENING",
                    "선택한 좌석 중 이 회차에 없는 좌석이 있습니다.");
        }
        TreeSet<Long> occupyingHolds = new TreeSet<>();
        for (SeatRow seat : seats) {
            if ("BOOKED".equals(seat.state())) {
                throw seatUnavailable();
            }
            if ("HELD".equals(seat.state())) {
                occupyingHolds.add(seat.holdId());
            }
        }
        List<HoldRow> stale = new ArrayList<>();
        for (HoldRow holder : lockHolds(List.copyOf(occupyingHolds)).values()) {
            if (holder.status() == HoldStatus.ACTIVE && !HoldPolicy.isExpired(now, holder.expiresAt())) {
                throw seatUnavailable();
            }
            stale.add(holder);
        }
        // 만료 작업자가 아직 반영하지 못한 만료 확보(다른 회원 포함)를 같은 트랜잭션에서 종결하고 좌석을 비운다.
        for (HoldRow holder : stale) {
            end(holder, HoldStatus.EXPIRED, now);
        }

        Instant expiresAt = HoldPolicy.expiresAt(now, screening.startsAt());
        long total = HoldPolicy.totalAmount(screening.basePrice(), seatIds.size());
        long holdId = insertHold(memberId, request.screeningId(), total, expiresAt, now);
        for (long seatId : seatIds) {
            jdbc.update("INSERT INTO hold_items (hold_id, screening_id, seat_id, unit_price) VALUES (?, ?, ?, ?)",
                    holdId, request.screeningId(), seatId, screening.basePrice());
        }
        List<Object> claimArgs = new ArrayList<>(List.of(holdId, request.screeningId()));
        claimArgs.addAll(seatIds);
        int claimed = jdbc.update("UPDATE screening_seats SET state = 'HELD', hold_id = ? "
                + "WHERE screening_id = ? AND state = 'AVAILABLE' AND hold_id IS NULL "
                + "AND seat_id IN (" + placeholders(seatIds.size()) + ")", claimArgs.toArray());
        if (claimed != seatIds.size()) {
            // 회차 잠금 아래에서는 생기지 않는다. 생기면 전체 롤백한다.
            throw seatUnavailable();
        }
        // 최초 확보 시각을 영구 기록한다. 이후 확보가 끝나도 지우지 않는다.
        jdbc.update("UPDATE screenings SET sales_locked_at = ? WHERE id = ? AND sales_locked_at IS NULL",
                ScreeningSchedule.toDb(now), request.screeningId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("holdId", holdId);
        body.put("status", HoldStatus.ACTIVE.name());
        body.put("seatIds", seatIds);
        body.put("expiresAt", expiresAt.toString());
        body.put("totalAmount", total);
        body.put("serverNow", now.toString());
        return body;
    }

    // 해제 ----------------------------------------------------------------------------------------------

    /**
     * 멱등 실행기가 기록에 접근하기 전에 호출하는 소유권 검사. 불변 소유자 정보만 읽고 잠그지 않는다.
     * 없는 확보·타인 확보는 같은 404다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireOwned(long memberId, long holdId) {
        Long owner = ownerOf(holdId);
        if (owner == null || owner != memberId) {
            throw holdNotFound();
        }
        lockRequestingMember(memberId);
    }

    /**
     * 요청 회원 행을 멱등 기록 선점보다 먼저 배타 잠근다(실행기의 접근 검사 단계에서 호출).
     *
     * <p>선점 INSERT는 회원 FK 검사로 회원 행에 공유 잠금을 건다. 이 잠금을 먼저 쥐지 않으면 같은 회원의 동시 요청들이 공유 잠금을
     * 함께 쥔 뒤 각자 배타 잠금으로 올리려다 데드락이 난다. 회원 잠금을 가장 먼저 얻어 기획의 잠금 순서(회원 → 회차 → …)를
     * 선점보다 앞에서 지킨다. 같은 회원의 요청끼리만 직렬화되고 다른 회원과는 서로 막지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockRequestingMember(long memberId) {
        lockMember(memberId);
    }

    /**
     * 본인 확보를 해제한다. 이미 종결(해제·만료)이면 바꾸지 않고 현재 종결 상태를 돌려준다. 만료가 지난 ACTIVE는 EXPIRED로 종결한다.
     * CONSUMED(예매 확정에 쓰인 확보)는 409.
     *
     * @return 200 본문 {holdId,status}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> release(long memberId, long holdId) {
        HoldRow hold = lockOwnedHold(memberId, holdId);
        Instant now = time.now();
        HoldStatus result = hold.status();
        if (hold.status() == HoldStatus.CONSUMED) {
            throw new ApiException(HttpStatus.CONFLICT, "HOLD_CONSUMED", "예매에 사용된 확보는 해제할 수 없습니다.");
        }
        if (hold.status() == HoldStatus.ACTIVE) {
            result = HoldPolicy.isExpired(now, hold.expiresAt()) ? HoldStatus.EXPIRED : HoldStatus.RELEASED;
            end(hold, result, now);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("holdId", holdId);
        body.put("status", result.name());
        return body;
    }

    // 만료 작업 -----------------------------------------------------------------------------------------

    /** 만료 시각이 지난 ACTIVE 확보 후보(잠그지 않는 읽기). 실제 만료는 {@link #expireIfDue(long)}가 잠금 아래 재검증한다. */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public List<Long> dueHoldIds(int limit) {
        Instant now = time.now();
        return jdbc.queryForList("SELECT id FROM holds WHERE status = 'ACTIVE' AND expires_at <= ? "
                + "ORDER BY expires_at, id LIMIT ?", Long.class, ScreeningSchedule.toDb(now), limit);
    }

    /**
     * 확보 하나를 만료 처리한다(독립 트랜잭션). 불변 소유자·회차를 읽고 회원 → 회차 → 확보 순서로 잠근 뒤, 여전히 ACTIVE이고
     * 만료 시각이 지났을 때만 EXPIRED로 바꾸고 그 확보가 소유한 좌석만 비운다.
     *
     * @return 이번 호출이 만료를 반영했으면 true. 이미 종결됐거나 아직 유효하면 아무것도 바꾸지 않고 false.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean expireIfDue(long holdId) {
        Map<String, Object> ref = immutableRef(holdId);
        if (ref == null) {
            return false;
        }
        lockMember(((Number) ref.get("member_id")).longValue());
        lockScreenings(new TreeSet<>(List.of(((Number) ref.get("screening_id")).longValue())));
        HoldRow hold = lockHolds(List.of(holdId)).get(holdId);
        Instant now = time.now();
        if (hold == null || hold.status() != HoldStatus.ACTIVE || !HoldPolicy.isExpired(now, hold.expiresAt())) {
            return false;
        }
        end(hold, HoldStatus.EXPIRED, now);
        return true;
    }

    // 조회 ----------------------------------------------------------------------------------------------

    /** 본인 확보 상세. 없는 확보·타인 확보는 같은 404. 상태는 기준 시각으로 본 실제 상태다(조회는 연장·변경하지 않는다). */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public HoldView view(long memberId, long holdId) {
        List<HoldRow> rows = jdbc.query("SELECT id, member_id, screening_id, status, expires_at, total_amount "
                + "FROM holds WHERE id = ? AND member_id = ?", HoldService::holdRow, holdId, memberId);
        if (rows.isEmpty()) {
            throw holdNotFound();
        }
        return toView(rows.get(0), time.now());
    }

    /** 본인의 현재 유효 확보(최대 1건). 만료가 지난 ACTIVE는 유효하지 않다. */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Optional<HoldView> current(long memberId) {
        Instant now = time.now();
        List<HoldRow> rows = jdbc.query("SELECT id, member_id, screening_id, status, expires_at, total_amount "
                        + "FROM holds WHERE member_id = ? AND status = 'ACTIVE' AND expires_at > ? ORDER BY id DESC LIMIT 1",
                HoldService::holdRow, memberId, ScreeningSchedule.toDb(now));
        return rows.isEmpty() ? Optional.empty() : Optional.of(toView(rows.get(0), now));
    }

    // 내부 ----------------------------------------------------------------------------------------------

    private HoldView toView(HoldRow hold, Instant now) {
        List<Long> seats = jdbc.queryForList("SELECT seat_id FROM hold_items WHERE hold_id = ? ORDER BY seat_id",
                Long.class, hold.id());
        HoldStatus status = HoldPolicy.effectiveStatus(hold.status(), now, hold.expiresAt());
        return new HoldView(hold.id(), hold.screeningId(), status.name(), seats, hold.expiresAt().toString(),
                hold.totalAmount(), now.toString(), null, null);
    }

    /** 소유권 확인 → 회원 → 회차 → 확보 잠금. 잠근 뒤의 확보 행을 돌려준다. */
    private HoldRow lockOwnedHold(long memberId, long holdId) {
        Map<String, Object> ref = immutableRef(holdId);
        if (ref == null || ((Number) ref.get("member_id")).longValue() != memberId) {
            throw holdNotFound();
        }
        lockMember(memberId);
        lockScreenings(new TreeSet<>(List.of(((Number) ref.get("screening_id")).longValue())));
        return lockHolds(List.of(holdId)).get(holdId);
    }

    /**
     * 확보를 종결 상태로 바꾸고 그 확보가 현재 소유한 좌석만 비운다. 호출자는 회차·확보 잠금을 쥐고 있어야 한다.
     * 두 문장 모두 조건부라 이미 종결된 확보나 새 소유자의 좌석에는 영향이 없다.
     */
    private void end(HoldRow hold, HoldStatus to, Instant now) {
        int changed = jdbc.update("UPDATE holds SET status = ?, ended_at = ? WHERE id = ? AND status = 'ACTIVE'",
                to.name(), ScreeningSchedule.toDb(now), hold.id());
        int freed = jdbc.update("UPDATE screening_seats SET state = 'AVAILABLE', hold_id = NULL "
                + "WHERE screening_id = ? AND hold_id = ? AND state = 'HELD'", hold.screeningId(), hold.id());
        if (changed == 1) {
            log.info("event=HOLD_ENDED status={} seats={}", to, freed);
        }
    }

    private void lockMember(long memberId) {
        List<Long> found = jdbc.queryForList("SELECT id FROM members WHERE id = ? FOR UPDATE", Long.class, memberId);
        if (found.isEmpty()) {
            // 인증된 회원이 없을 수는 없다(회원 삭제 기능 없음). 정보를 드러내지 않고 실패시킨다.
            throw new IllegalStateException("authenticated member row missing");
        }
    }

    /** 오름차순으로 회차 행을 배타 잠금한다. 없는 회차는 결과에 없다. */
    private Map<Long, ScreeningRow> lockScreenings(TreeSet<Long> screeningIds) {
        Map<Long, ScreeningRow> rows = new TreeMap<>();
        for (long screeningId : screeningIds) {
            jdbc.query("SELECT id, starts_at, base_price FROM screenings WHERE id = ? FOR UPDATE",
                    (rs, n) -> new ScreeningRow(rs.getLong("id"),
                            ScreeningSchedule.fromDb(rs.getObject("starts_at", LocalDateTime.class)),
                            rs.getLong("base_price")),
                    screeningId).forEach(row -> rows.put(row.id(), row));
        }
        return rows;
    }

    /** 오름차순으로 확보 행을 배타 잠금하고 잠근 뒤의 값을 돌려준다. */
    private Map<Long, HoldRow> lockHolds(List<Long> holdIds) {
        Map<Long, HoldRow> rows = new TreeMap<>();
        for (long holdId : new TreeSet<>(holdIds)) {
            jdbc.query("SELECT id, member_id, screening_id, status, expires_at, total_amount FROM holds "
                    + "WHERE id = ? FOR UPDATE", HoldService::holdRow, holdId).forEach(row -> rows.put(row.id(), row));
        }
        return Collections.unmodifiableMap(rows);
    }

    /** 확보의 불변 정보(소유 회원·회차). 잠그지 않는다. */
    private Map<String, Object> immutableRef(long holdId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT member_id, screening_id FROM holds WHERE id = ?", holdId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Long ownerOf(long holdId) {
        List<Long> owner = jdbc.queryForList("SELECT member_id FROM holds WHERE id = ?", Long.class, holdId);
        return owner.isEmpty() ? null : owner.get(0);
    }

    private long insertHold(long memberId, long screeningId, long total, Instant expiresAt, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO holds (member_id, screening_id, status, total_amount, expires_at, created_at) "
                            + "VALUES (?, ?, 'ACTIVE', ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, memberId);
            statement.setLong(2, screeningId);
            statement.setLong(3, total);
            statement.setObject(4, ScreeningSchedule.toDb(expiresAt));
            statement.setObject(5, ScreeningSchedule.toDb(now));
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private static HoldRow holdRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new HoldRow(rs.getLong("id"), rs.getLong("member_id"), rs.getLong("screening_id"),
                HoldStatus.valueOf(rs.getString("status")),
                ScreeningSchedule.fromDb(rs.getObject("expires_at", LocalDateTime.class)), rs.getLong("total_amount"));
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static Object[] args(long first, List<Long> rest) {
        List<Object> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all.toArray();
    }

    private static ApiException seatUnavailable() {
        return new ApiException(HttpStatus.CONFLICT, "SEAT_UNAVAILABLE", "이미 다른 사용자가 선택한 좌석이 포함되어 있습니다.");
    }

    private static ApiException holdNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, HOLD_NOT_FOUND, "확보를 찾을 수 없습니다.");
    }
}
