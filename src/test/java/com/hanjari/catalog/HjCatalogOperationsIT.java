package com.hanjari.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hanjari.auth.SessionCookieConfig;
import com.hanjari.screening.ScreeningSchedule;
import com.hanjari.testsupport.ConcurrentRunner;
import com.hanjari.testsupport.MutableClock;
import com.hanjari.testsupport.TestDataCleaner;
import com.hanjari.testsupport.TestDatabaseGuard;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * HJ-004: 실제 MySQL(hanjari_test)과 실제 HTTP 서버(임의 포트)로 공개 조회·합성 초기 데이터·운영 API를 검증한다.
 *
 * <p>판정은 응답과 함께 DB 사후 상태로 한다. 동시성 검증은 시작 barrier와 유한 timeout을 가진 {@link ConcurrentRunner}로
 * 독립 HTTP 요청(각각 별도 DB 연결·트랜잭션)을 동시에 보내고, 임의 sleep을 쓰지 않는다. 운영자는 격리된 테스트 DB에서만
 * 역할을 바꾼 fixture이며 실제 운영자 승인을 대신하지 않는다. 판매 잠금은 확보 기능(HJ-005) 이전이라 DB fixture로 설정한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "hanjari.security.allowed-origins=http://localhost:5173")
class HjCatalogOperationsIT {

    private static final String ORIGIN = "http://localhost:5173";
    private static final String COOKIE = SessionCookieConfig.COOKIE_NAME;
    private static final String PASSWORD = "catalog password 1";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CONCURRENCY_TIMEOUT = Duration.ofSeconds(90);
    private static final String SEED_SCRIPT = "db/devdata/R__hj004_synthetic_catalog.sql";

    private static TestDatabaseGuard.Settings settings;

    @TestConfiguration
    static class ControlledClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2030-01-01T00:00:00Z"));
        }
    }

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        settings = TestDatabaseGuard.verifiedFromEnvironment();
        TestDatabaseGuard.register(registry, settings);
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MutableClock clock;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void cleanAndOpenNewRateWindow() throws SQLException {
        clean();
        // 로그인 요청 제한(분 단위 창)이 테스트 사이에 이어지지 않게 한다. 회차 판정은 DB 시각을 쓰므로 영향이 없다.
        clock.advance(Duration.ofHours(1));
    }

    /** 다른 IT(예: MovieApiIT의 movies 일괄 삭제)가 회차 FK에 막히지 않도록 끝난 뒤에도 비운다. */
    @AfterEach
    void cleanAfter() throws SQLException {
        clean();
    }

    // AC1 공개 조회 -------------------------------------------------------------------------------------

    @Test
    void publicMovieListPaginatesInIdOrderWithoutLoginAndKeepsDetailContract() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            ids.add(insertMovie(String.format("목록 영화 %02d", 24 - i), 60 + i, i % 2 == 0 ? "ALL" : "AGE_15"));
        }
        Browser anonymous = new Browser();

        Reply first = anonymous.get("/api/v1/movies");
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.keys("$")).containsExactlyInAnyOrder("items", "page", "size", "hasNext");
        assertThat((Integer) first.json("$.page")).isZero();
        assertThat((Integer) first.json("$.size")).isEqualTo(20);
        assertThat((Boolean) first.json("$.hasNext")).isTrue();
        assertThat(first.longs("$.items[*].id")).containsExactlyElementsOf(ids.subList(0, 20));
        for (int i = 0; i < 20; i++) {
            assertThat(first.keys("$.items[" + i + "]")).containsExactlyInAnyOrder("id", "title", "runtimeMinutes",
                    "ageRating");
        }
        assertThat((String) first.json("$.items[0].title")).isEqualTo("목록 영화 24");
        assertThat((Integer) first.json("$.items[0].runtimeMinutes")).isEqualTo(60);

        Reply second = anonymous.get("/api/v1/movies?page=1&size=20");
        assertThat(second.longs("$.items[*].id")).containsExactlyElementsOf(ids.subList(20, 25));
        assertThat((Boolean) second.json("$.hasNext")).isFalse();
        Reply all = anonymous.get("/api/v1/movies?size=100");
        assertThat(all.longs("$.items[*].id")).containsExactlyElementsOf(ids);
        assertThat((Boolean) all.json("$.hasNext")).isFalse();
        Reply exact = anonymous.get("/api/v1/movies?page=4&size=5");
        assertThat(exact.longs("$.items[*].id")).containsExactlyElementsOf(ids.subList(20, 25));
        assertThat((Boolean) exact.json("$.hasNext")).isFalse();
        Reply beyond = anonymous.get("/api/v1/movies?page=3");
        assertThat(beyond.longs("$.items[*].id")).isEmpty();
        assertThat((Boolean) beyond.json("$.hasNext")).isFalse();
        // 같은 요청은 같은 결과(안정 정렬).
        assertThat(anonymous.get("/api/v1/movies").body()).isEqualTo(first.body());

        // 기존 영화 상세 계약 유지: 네 필드, version 같은 내부 값 없음.
        Reply detail = anonymous.get("/api/v1/movies/" + ids.get(3));
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.keys("$")).containsExactlyInAnyOrder("id", "title", "runtimeMinutes", "ageRating");
        assertThat(((Number) detail.json("$.id")).longValue()).isEqualTo(ids.get(3));
        assertThat((String) detail.json("$.ageRating")).isEqualTo("AGE_15");
    }

    @Test
    void publicQueryInputErrorsReturnFixedCodeAndMessageOnly() throws Exception {
        long movieId = insertMovie("입력 오류 영화", 100, "ALL");
        Browser anonymous = new Browser();
        Map<String, String> badRequests = new java.util.LinkedHashMap<>();
        for (String page : new String[] {"-1", "01", "abc", "1.5", "1000001", "", "1&page=2"}) {
            badRequests.put("/api/v1/movies?page=" + page, "INVALID_PAGE");
        }
        for (String size : new String[] {"0", "101", "-5", "abc", "", "020"}) {
            badRequests.put("/api/v1/movies?size=" + size, "INVALID_SIZE");
        }
        badRequests.put("/api/v1/screenings", "INVALID_MOVIE_ID");
        badRequests.put("/api/v1/screenings?date=2031-03-01", "INVALID_MOVIE_ID");
        for (String id : new String[] {"0", "-1", "01", "abc", "9223372036854775808"}) {
            badRequests.put("/api/v1/screenings?movieId=" + id + "&date=2031-03-01", "INVALID_MOVIE_ID");
        }
        badRequests.put("/api/v1/screenings?movieId=" + movieId, "INVALID_DATE");
        for (String date : new String[] {"2031-02-30", "2031-3-1", "20310301", "2031-03-01T00:00:00Z", ""}) {
            badRequests.put("/api/v1/screenings?movieId=" + movieId + "&date=" + date, "INVALID_DATE");
        }
        for (String id : new String[] {"0", "01", "abc", "-1"}) {
            badRequests.put("/api/v1/screenings/" + id + "/seats", "INVALID_SCREENING_ID");
        }
        for (Map.Entry<String, String> entry : badRequests.entrySet()) {
            Reply reply = anonymous.get(entry.getKey());
            assertThat(reply.status()).as(entry.getKey()).isEqualTo(400);
            assertThat(reply.keys("$")).as(entry.getKey()).containsExactlyInAnyOrder("code", "message");
            assertThat((String) reply.json("$.code")).as(entry.getKey()).isEqualTo(entry.getValue());
            assertThat(reply.body()).doesNotContain("abc").doesNotContain("Exception");
        }

        Reply missingMovie = anonymous.get("/api/v1/screenings?movieId=" + (movieId + 1000) + "&date=2031-03-01");
        assertThat(missingMovie.status()).isEqualTo(404);
        assertThat((String) missingMovie.json("$.code")).isEqualTo("MOVIE_NOT_FOUND");
        Reply missingScreening = anonymous.get("/api/v1/screenings/9223372036854775807/seats");
        assertThat(missingScreening.status()).isEqualTo(404);
        assertThat((String) missingScreening.json("$.code")).isEqualTo("SCREENING_NOT_FOUND");
        // 데이터가 없는 날짜는 빈 목록이다.
        Reply empty = anonymous.get("/api/v1/screenings?movieId=" + movieId + "&date=2031-03-01");
        assertThat(empty.status()).isEqualTo(200);
        assertThat(empty.longs("$.items[*].id")).isEmpty();
    }

    @Test
    void screeningsAreFilteredByKoreanDateAndOrderedByStartThenId() throws Exception {
        long movie = insertMovie("날짜 영화", 60, "AGE_12");
        long other = insertMovie("다른 영화", 60, "ALL");
        long hall1 = insertTheater("날짜 1관", "A", 2);
        long hall2 = insertTheater("날짜 2관", "A", 2);
        // 일부러 시작 순서와 다른 순서로 넣는다. 한국 날짜 2031-03-01 = [02-28T15:00Z, 03-01T15:00Z).
        long last = insertScreening(movie, hall1, "2031-03-01T14:59:59.999999Z", 60, 9000);
        long midnight = insertScreening(movie, hall1, "2031-02-28T15:00:00Z", 60, 10000);
        long tieFirst = insertScreening(movie, hall2, "2031-03-01T03:00:00Z", 60, 11000);
        long tieSecond = insertScreening(movie, hall1, "2031-03-01T03:00:00Z", 60, 12000);
        insertScreening(movie, hall2, "2031-02-28T14:59:59.999999Z", 60, 1);  // KST 02-28 23:59:59.999999
        insertScreening(movie, hall2, "2031-03-01T15:00:00Z", 60, 1);         // KST 03-02 00:00
        insertScreening(other, hall2, "2031-03-01T06:00:00Z", 60, 1);         // 다른 영화
        long past = insertScreening(movie, hall1, "2020-01-01T03:00:00Z", 60, 1);
        // 매진 회차는 예매 불가로 표시한다.
        jdbcTemplate.update("UPDATE screening_seats SET state = 'BOOKED' WHERE screening_id = ?", last);

        Browser anonymous = new Browser();
        Reply day = anonymous.get("/api/v1/screenings?movieId=" + movie + "&date=2031-03-01");
        assertThat(day.status()).isEqualTo(200);
        assertThat(day.keys("$")).containsExactly("items");
        assertThat(day.longs("$.items[*].id")).containsExactly(midnight, tieFirst, tieSecond, last);
        assertThat(day.keys("$.items[0]")).containsExactlyInAnyOrder("id", "movieId", "theaterId", "theaterName",
                "startsAt", "endsAt", "basePrice", "bookable");
        assertThat((String) day.json("$.items[0].startsAt")).isEqualTo("2031-02-28T15:00:00Z");
        assertThat((String) day.json("$.items[0].endsAt")).isEqualTo("2031-02-28T16:00:00Z");
        assertThat((String) day.json("$.items[0].theaterName")).isEqualTo("날짜 1관");
        assertThat(((Number) day.json("$.items[0].basePrice")).longValue()).isEqualTo(10000);
        assertThat(((Number) day.json("$.items[1].theaterId")).longValue()).isEqualTo(hall2);
        assertThat((String) day.json("$.items[3].startsAt")).isEqualTo("2031-03-01T14:59:59.999999Z");
        assertThat(JsonPath.<List<Boolean>>read(day.body(), "$.items[*].bookable")).containsExactly(true, true, true, false);
        assertThat(anonymous.get("/api/v1/screenings?movieId=" + movie + "&date=2031-03-01").body())
                .as("stable ordering").isEqualTo(day.body());

        Reply nextDay = anonymous.get("/api/v1/screenings?movieId=" + movie + "&date=2031-03-02");
        assertThat(nextDay.longs("$.items[*].id")).hasSize(1);
        Reply pastDay = anonymous.get("/api/v1/screenings?movieId=" + movie + "&date=2020-01-01");
        assertThat(pastDay.longs("$.items[*].id")).containsExactly(past);
        assertThat((Boolean) pastDay.json("$.items[0].bookable")).as("already started").isFalse();
    }

    @Test
    void seatMapShowsPositionsAndStatusesWithoutAnyoneIdentity() throws Exception {
        long movie = insertMovie("좌석 영화", 100, "ALL");
        long theater = insertTheater("좌석관", null, 0);
        // 일부러 정렬과 다른 순서로 넣는다. 기대 순서: A1, A2, B1, AA1 (행 이름 길이 → 행 → 번호).
        long b1 = insertSeat(theater, "B", 1);
        long aa1 = insertSeat(theater, "AA", 1);
        long a2 = insertSeat(theater, "A", 2);
        long a1 = insertSeat(theater, "A", 1);
        long screening = insertScreening(movie, theater, "2031-04-01T01:00:00Z", 100, 15000);
        jdbcTemplate.update("UPDATE screening_seats SET state = 'HELD' WHERE screening_id = ? AND seat_id = ?",
                screening, a2);
        jdbcTemplate.update("UPDATE screening_seats SET state = 'BOOKED' WHERE screening_id = ? AND seat_id = ?",
                screening, b1);
        long memberId = signup("seat_viewer");
        Browser member = login("seat_viewer");

        Instant before = dbNow();
        Reply anonymousView = new Browser().get("/api/v1/screenings/" + screening + "/seats");
        Reply memberView = member.get("/api/v1/screenings/" + screening + "/seats");

        for (Reply view : List.of(anonymousView, memberView)) {
            assertThat(view.status()).isEqualTo(200);
            assertThat(view.keys("$")).containsExactlyInAnyOrder("screeningId", "serverNow", "seats");
            assertThat(((Number) view.json("$.screeningId")).longValue()).isEqualTo(screening);
            assertThat(view.longs("$.seats[*].seatId")).containsExactly(a1, a2, b1, aa1);
            assertThat(JsonPath.<List<String>>read(view.body(), "$.seats[*].row")).containsExactly("A", "A", "B", "AA");
            assertThat(JsonPath.<List<Integer>>read(view.body(), "$.seats[*].number")).containsExactly(1, 2, 1, 1);
            assertThat(JsonPath.<List<String>>read(view.body(), "$.seats[*].status"))
                    .containsExactly("AVAILABLE", "HELD", "BOOKED", "AVAILABLE");
            for (int i = 0; i < 4; i++) {
                assertThat(view.keys("$.seats[" + i + "]")).containsExactlyInAnyOrder("seatId", "row", "number",
                        "status", "mine");
                assertThat((Boolean) view.json("$.seats[" + i + "].mine")).isFalse();
            }
            Instant serverNow = Instant.parse((String) view.json("$.serverNow"));
            assertThat(serverNow).isBetween(before.minusSeconds(1), dbNow().plusSeconds(1));
            assertThat(view.body()).doesNotContain("seat_viewer").doesNotContain("memberId")
                    .doesNotContain("holdId").doesNotContain("reservationId").doesNotContain("loginId");
        }
        assertThat(memberView.longs("$.seats[*].seatId")).isEqualTo(anonymousView.longs("$.seats[*].seatId"));
        assertThat(memberId).isPositive();
    }

    // AC2 합성 초기 데이터 --------------------------------------------------------------------------------

    @Test
    void syntheticSeedReappliesIdempotentlyWithMoviesTheatersSeatsOnly() throws Exception {
        String script = new ClassPathResource(SEED_SCRIPT).getContentAsString(StandardCharsets.UTF_8);
        Pattern forbidden = Pattern.compile(
                "(?is)insert\\s+(ignore\\s+)?into\\s+`?(members|spring_session|spring_session_attributes|screenings|screening_seats)\\b");
        assertThat(forbidden.matcher(script).find()).as("seed creates no accounts, sessions or screenings").isFalse();

        applySeed();
        Map<String, List<Map<String, Object>>> afterFirst = seedSnapshot();
        assertThat(afterFirst.get("theaters")).extracting(row -> row.get("name")).containsExactly("1관", "2관", "3관");
        assertThat(afterFirst.get("seats")).hasSize(5 * 10 + 4 * 8 + 3 * 6);
        assertThat(afterFirst.get("movies")).extracting(row -> row.get("title"))
                .containsExactly("한자리 합성 영화 가", "한자리 합성 영화 나");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT theater_id, row_label, seat_number) FROM seats", Integer.class))
                .isEqualTo(afterFirst.get("seats").size());

        applySeed();
        applySeed();
        assertThat(seedSnapshot()).as("re-applying keeps the exact same rows and ids").isEqualTo(afterFirst);

        // 일부 좌석이 사라진 상태에서 다시 적용하면 빠진 좌석만 보충한다(기존 행 ID는 유지).
        jdbcTemplate.update("DELETE FROM seats WHERE row_label = 'E'");
        applySeed();
        Map<String, List<Map<String, Object>>> repaired = seedSnapshot();
        assertThat(repaired.get("seats")).hasSize(afterFirst.get("seats").size());
        assertThat(repaired.get("theaters")).isEqualTo(afterFirst.get("theaters"));
        assertThat(repaired.get("movies")).isEqualTo(afterFirst.get("movies"));

        for (String table : new String[] {"members", "SPRING_SESSION", "SPRING_SESSION_ATTRIBUTES", "screenings",
                "screening_seats"}) {
            assertThat(count(table)).as(table).isZero();
        }
        // 합성 데이터는 공개 조회로 바로 보인다(계정 없이).
        Reply movies = new Browser().get("/api/v1/movies");
        assertThat(movies.status()).isEqualTo(200);
        assertThat(movies.longs("$.items[*].id")).hasSize(2);
    }

    @Test
    void databaseEnforcesSeatUniquenessAndScreeningInventoryConstraints() {
        long movie = insertMovie("제약 영화", 100, "ALL");
        long hall1 = insertTheater("제약 1관", "A", 2);
        long hall2 = insertTheater("제약 2관", "A", 2);
        long screening = insertScreening(movie, hall1, "2031-05-01T01:00:00Z", 100, 1000);
        long otherHallSeat = jdbcTemplate.queryForObject(
                "SELECT MIN(id) FROM seats WHERE theater_id = ?", Long.class, hall2);
        long ownSeat = jdbcTemplate.queryForObject(
                "SELECT MIN(id) FROM seats WHERE theater_id = ?", Long.class, hall1);
        int seatsBefore = count("seats");
        int inventoryBefore = count("screening_seats");

        // 좌석 위치 고유성·형식.
        assertRejected("INSERT INTO seats (theater_id, row_label, seat_number) VALUES (?, 'A', 1)", hall1);
        assertRejected("INSERT INTO seats (theater_id, row_label, seat_number) VALUES (?, 'a', 3)", hall1);
        assertRejected("INSERT INTO seats (theater_id, row_label, seat_number) VALUES (?, 'A', 0)", hall1);
        assertRejected("INSERT INTO seats (theater_id, row_label, seat_number) VALUES (?, 'ABC', 1)", hall1);
        assertRejected("INSERT INTO theaters (name) VALUES ('제약 1관')");
        // 회차 입력 제약: 시작 < 종료, 가격 0~1,000,000, 유효 참조.
        assertRejected("INSERT INTO screenings (movie_id, theater_id, starts_at, ends_at, base_price) "
                + "VALUES (?, ?, '2031-06-01 03:00:00', '2031-06-01 03:00:00', 0)", movie, hall1);
        assertRejected("INSERT INTO screenings (movie_id, theater_id, starts_at, ends_at, base_price) "
                + "VALUES (?, ?, '2031-06-01 03:00:00', '2031-06-01 04:00:00', 1000001)", movie, hall1);
        assertRejected("INSERT INTO screenings (movie_id, theater_id, starts_at, ends_at, base_price) "
                + "VALUES (?, ?, '2031-06-01 03:00:00', '2031-06-01 04:00:00', -1)", movie, hall1);
        assertRejected("INSERT INTO screenings (movie_id, theater_id, starts_at, ends_at, base_price) "
                + "VALUES (?, ?, '2031-06-01 03:00:00', '2031-06-01 04:00:00', 0)", movie + 999, hall1);
        // inventory: 다른 상영관 좌석·중복 좌석·알 수 없는 상태는 거부된다.
        assertRejected("INSERT INTO screening_seats (screening_id, theater_id, seat_id) VALUES (?, ?, ?)",
                screening, hall1, otherHallSeat);
        assertRejected("INSERT INTO screening_seats (screening_id, theater_id, seat_id) VALUES (?, ?, ?)",
                screening, hall2, otherHallSeat);
        assertRejected("INSERT INTO screening_seats (screening_id, theater_id, seat_id) VALUES (?, ?, ?)",
                screening, hall1, ownSeat);
        assertRejected("UPDATE screening_seats SET state = 'SOLD' WHERE screening_id = ?", screening);
        // cascade 삭제 없음: 회차가 참조하는 영화·상영관, inventory가 참조하는 좌석은 지울 수 없다.
        assertRejected("DELETE FROM movies WHERE id = ?", movie);
        assertRejected("DELETE FROM theaters WHERE id = ?", hall1);
        assertRejected("DELETE FROM seats WHERE id = ?", ownSeat);

        assertThat(count("seats")).isEqualTo(seatsBefore);
        assertThat(count("screening_seats")).isEqualTo(inventoryBefore).isEqualTo(2);
        assertThat(count("screenings")).isEqualTo(1);
    }

    // AC3 상영관 중첩 ------------------------------------------------------------------------------------

    @Test
    void concurrentOverlappingRegistrationsAdmitExactlyOnePerTheater() throws Exception {
        long movie = insertMovie("동시 등록 영화", 100, "ALL");
        long hallA = insertTheater("동시 A관", "A", 3);
        long hallB = insertTheater("동시 B관", "A", 2);
        Browser operator = operator("race_operator");
        Instant base = Instant.parse("2031-07-01T01:00:00Z");

        // 같은 상영관 안에서는 모든 쌍이 [시작, 종료+10분) 기준으로 겹친다(시작 차이 ≤ 15분 < 110분).
        List<ConcurrentRunner.BarrierTask<Reply>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            long hall = i % 2 == 0 ? hallA : hallB;
            String body = screeningBody(movie, hall, base.plus(Duration.ofMinutes(5L * (i / 2))).toString(), 8000 + i);
            tasks.add(gate -> {
                Browser mine = operator.copy();
                gate.await();
                return mine.post("/api/v1/ops/screenings", body);
            });
        }
        List<Reply> replies = ConcurrentRunner.runWithGate(CONCURRENCY_TIMEOUT, tasks);

        // 판정 기준은 DB: 상영관마다 회차 정확히 1개, inventory는 그 상영관 좌석 수와 같다.
        assertThat(screeningCount(hallA)).isEqualTo(1);
        assertThat(screeningCount(hallB)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM screening_seats ss JOIN screenings s ON s.id = ss.screening_id "
                        + "WHERE s.theater_id = ?", Integer.class, hallA)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM screening_seats ss JOIN screenings s ON s.id = ss.screening_id "
                        + "WHERE s.theater_id = ?", Integer.class, hallB)).isEqualTo(2);
        List<Integer> statuses = replies.stream().map(Reply::status).toList();
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(2);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(6);
        for (Reply reply : replies) {
            if (reply.status() == 409) {
                assertThat((String) reply.json("$.code")).isEqualTo("SCREENING_OVERLAP");
            } else {
                long id = ((Number) reply.json("$.id")).longValue();
                long theater = ((Number) reply.json("$.theaterId")).longValue();
                assertThat(jdbcTemplate.queryForObject("SELECT theater_id FROM screenings WHERE id = ?", Long.class, id))
                        .isEqualTo(theater);
            }
        }
        System.out.println("[HJ-004] concurrent overlapping registrations statuses=" + statuses
                + ", screeningsPerTheater=1/1");
    }

    @Test
    void preparationTimeBoundaryIsHalfOpenForCreateMoveAndConcurrentNeighbors() throws Exception {
        long movie = insertMovie("경계 영화", 120, "ALL");
        long hall = insertTheater("경계관", "A", 2);
        long otherHall = insertTheater("경계 옆관", "A", 2);
        Browser operator = operator("edge_operator");

        Reply anchor = operator.post("/api/v1/ops/screenings", screeningBody(movie, hall, "2031-08-01T10:00:00Z", 10000));
        assertThat(anchor.status()).isEqualTo(201);
        assertThat((String) anchor.json("$.endsAt")).isEqualTo("2031-08-01T12:00:00Z");
        assertThat((Integer) anchor.json("$.version")).isZero();
        assertThat((Boolean) anchor.json("$.salesLocked")).isFalse();

        // 뒤쪽 경계: 종료 12:00 + 준비 10분.
        Reply tooEarly = operator.post("/api/v1/ops/screenings",
                screeningBody(movie, hall, "2031-08-01T12:09:59.999999Z", 10000));
        assertThat(tooEarly.status()).isEqualTo(409);
        assertThat((String) tooEarly.json("$.code")).isEqualTo("SCREENING_OVERLAP");
        Reply exactAfter = operator.post("/api/v1/ops/screenings",
                screeningBody(movie, hall, "2031-08-01T12:10:00Z", 10000));
        assertThat(exactAfter.status()).isEqualTo(201);
        // 앞쪽 경계: 앞 회차 종료+10분이 10:00과 같으면 허용(07:50 시작, 120분).
        Reply tooLate = operator.post("/api/v1/ops/screenings",
                screeningBody(movie, hall, "2031-08-01T07:50:00.000001Z", 10000));
        assertThat(tooLate.status()).isEqualTo(409);
        Reply exactBefore = operator.post("/api/v1/ops/screenings",
                screeningBody(movie, hall, "2031-08-01T07:50:00Z", 10000));
        assertThat(exactBefore.status()).isEqualTo(201);
        assertThat(screeningCount(hall)).isEqualTo(3);

        // 이동·시간 변경도 같은 규칙: 다른 상영관 회차를 겹치는 시각으로 옮기면 409, 경계에 맞추면 200.
        Reply elsewhere = operator.post("/api/v1/ops/screenings",
                screeningBody(movie, otherHall, "2031-08-01T11:00:00Z", 10000));
        long elsewhereId = ((Number) elsewhere.json("$.id")).longValue();
        Reply moveOverlap = operator.patch("/api/v1/ops/screenings/" + elsewhereId,
                "{\"version\":0,\"theaterId\":" + hall + "}");
        assertThat(moveOverlap.status()).isEqualTo(409);
        assertThat((String) moveOverlap.json("$.code")).isEqualTo("SCREENING_OVERLAP");
        Reply moveFit = operator.patch("/api/v1/ops/screenings/" + elsewhereId,
                "{\"version\":0,\"theaterId\":" + hall + ",\"startsAt\":\"2031-08-01T14:20:00Z\"}");
        assertThat(moveFit.status()).isEqualTo(200);
        assertThat((String) moveFit.json("$.endsAt")).isEqualTo("2031-08-01T16:20:00Z");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM screening_seats WHERE screening_id = ? AND theater_id = ?", Integer.class,
                elsewhereId, hall)).as("inventory moved to the new theater's seats").isEqualTo(2);

        // 동시에 들어온 경계 이웃 두 건(16:30~18:30, 18:40~)은 서로 겹치지 않으므로 둘 다 성공한다.
        List<ConcurrentRunner.BarrierTask<Reply>> neighbors = new ArrayList<>();
        for (String start : new String[] {"2031-08-01T16:30:00Z", "2031-08-01T18:40:00Z"}) {
            String body = screeningBody(movie, hall, start, 10000);
            neighbors.add(gate -> {
                Browser mine = operator.copy();
                gate.await();
                return mine.post("/api/v1/ops/screenings", body);
            });
        }
        assertThat(ConcurrentRunner.runWithGate(CONCURRENCY_TIMEOUT, neighbors)).extracting(Reply::status)
                .containsExactly(201, 201);
        assertThat(screeningCount(hall)).isEqualTo(6);
        assertNoOverlapInDatabase(hall);

        // 과거·현재 시작은 거부(DB 시각 기준), 없는 참조는 404.
        Reply past = operator.post("/api/v1/ops/screenings", screeningBody(movie, hall, "2020-01-01T00:00:00Z", 0));
        assertThat(past.status()).isEqualTo(400);
        assertThat((String) past.json("$.code")).isEqualTo("STARTS_AT_NOT_FUTURE");
        Reply noTheater = operator.post("/api/v1/ops/screenings",
                screeningBody(movie, otherHall + 1000, "2031-09-01T00:00:00Z", 0));
        assertThat(noTheater.status()).isEqualTo(404);
        assertThat((String) noTheater.json("$.code")).isEqualTo("THEATER_NOT_FOUND");
        Reply noMovie = operator.post("/api/v1/ops/screenings",
                screeningBody(movie + 1000, hall, "2031-09-01T00:00:00Z", 0));
        assertThat(noMovie.status()).isEqualTo(404);
        assertThat((String) noMovie.json("$.code")).isEqualTo("MOVIE_NOT_FOUND");
        assertThat(screeningCount(hall)).isEqualTo(6);

        // 저장 상한: 형식상 유효한 마지막 시각이라도 종료+준비 시간이 DATETIME(6) 범위를 넘으면 500이 아니라 400이다.
        String beforeRange = dataSnapshot();
        for (String start : new String[] {"9999-12-31T23:59:00Z", "9999-12-31T21:50:00Z"}) {
            Reply outOfRange = operator.post("/api/v1/ops/screenings", screeningBody(movie, otherHall, start, 0));
            assertThat(outOfRange.status()).as(start).isEqualTo(400);
            assertThat(outOfRange.keys("$")).containsExactlyInAnyOrder("code", "message");
            assertThat((String) outOfRange.json("$.code")).as(start).isEqualTo("STARTS_AT_OUT_OF_RANGE");
        }
        Reply moveOutOfRange = operator.patch("/api/v1/ops/screenings/" + elsewhereId,
                "{\"version\":1,\"startsAt\":\"9999-12-31T23:00:00Z\"}");
        assertThat(moveOutOfRange.status()).isEqualTo(400);
        assertThat((String) moveOutOfRange.json("$.code")).isEqualTo("STARTS_AT_OUT_OF_RANGE");
        assertThat(dataSnapshot()).as("out-of-range input writes nothing").isEqualTo(beforeRange);
        // 점유 종료가 정확히 9999-12-31T23:59:59.999999Z인 마지막 시작은 저장되고, 그 앞 경계의 중첩 검사도 동작한다.
        Reply lastFit = operator.post("/api/v1/ops/screenings",
                screeningBody(movie, otherHall, "9999-12-31T21:49:59.999999Z", 0));
        assertThat(lastFit.status()).isEqualTo(201);
        assertThat((String) lastFit.json("$.endsAt")).isEqualTo("9999-12-31T23:49:59.999999Z");
        Reply lastOverlap = operator.post("/api/v1/ops/screenings",
                screeningBody(movie, otherHall, "9999-12-31T19:40:00Z", 0));
        assertThat(lastOverlap.status()).isEqualTo(409);
        assertThat((String) lastOverlap.json("$.code")).isEqualTo("SCREENING_OVERLAP");
    }

    // AC4 운영 권한 -------------------------------------------------------------------------------------

    @Test
    void operationsRequireOperatorRoleOriginAndCsrfBeforeAnyWrite() throws Exception {
        long movie = insertMovie("권한 영화", 100, "ALL");
        long hall = insertTheater("권한관", "A", 2);
        long screening = insertScreening(movie, hall, "2031-10-01T01:00:00Z", 100, 5000);
        signup("plain_member");
        Browser member = login("plain_member");
        Browser operator = operator("ops_operator");
        Browser anonymous = newBrowserWithCsrf();

        List<String[]> requests = List.of(
                new String[] {"POST", "/api/v1/ops/movies", "{\"title\":\"새 영화\",\"runtimeMinutes\":90,\"ageRating\":\"ALL\"}"},
                new String[] {"PATCH", "/api/v1/ops/movies/" + movie, "{\"version\":0,\"title\":\"바뀐 제목\"}"},
                new String[] {"POST", "/api/v1/ops/screenings", screeningBody(movie, hall, "2031-10-02T01:00:00Z", 1)},
                new String[] {"PATCH", "/api/v1/ops/screenings/" + screening, "{\"version\":0,\"basePrice\":7000}"},
                new String[] {"DELETE", "/api/v1/ops/screenings/" + screening, null});
        String before = dataSnapshot();
        for (String[] request : requests) {
            String label = request[0] + " " + request[1];
            Reply unauthenticated = anonymous.send(request[0], request[1], request[2], ORIGIN, anonymous.csrfToken);
            assertThat(unauthenticated.status()).as("anonymous " + label).isEqualTo(401);
            assertThat((String) unauthenticated.json("$.code")).isEqualTo("UNAUTHENTICATED");

            Reply forbidden = member.send(request[0], request[1], request[2], ORIGIN, member.csrfToken);
            assertThat(forbidden.status()).as("member " + label).isEqualTo(403);
            assertThat((String) forbidden.json("$.code")).isEqualTo("FORBIDDEN");

            Reply noCsrf = operator.send(request[0], request[1], request[2], ORIGIN, null);
            assertThat(noCsrf.status()).as("operator without csrf " + label).isEqualTo(403);
            assertThat((String) noCsrf.json("$.code")).isEqualTo("CSRF_INVALID");

            Reply foreign = operator.send(request[0], request[1], request[2], "http://evil.example", operator.csrfToken);
            assertThat(foreign.status()).as("operator foreign origin " + label).isEqualTo(403);
            assertThat((String) foreign.json("$.code")).isEqualTo("ORIGIN_NOT_ALLOWED");
        }
        assertThat(dataSnapshot()).as("no write before authorization").isEqualTo(before);

        // 격리 운영자 fixture: 같은 요청이 성공한다.
        int[] expected = {201, 200, 201, 200, 204};
        for (int i = 0; i < requests.size(); i++) {
            String[] request = requests.get(i);
            Reply ok = operator.send(request[0], request[1], request[2], ORIGIN, operator.csrfToken);
            assertThat(ok.status()).as("operator " + request[0] + " " + request[1]).isEqualTo(expected[i]);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM movies WHERE id = ?", String.class, movie))
                .isEqualTo("바뀐 제목");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM movies WHERE title = '새 영화'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM screenings WHERE id = ?", Integer.class, screening))
                .isZero();
        assertThat(screeningCount(hall)).isEqualTo(1);

        // 범위 밖 API는 없다: 영화 삭제·상영관/좌석 편집·권한 변경.
        assertThat(operator.send("DELETE", "/api/v1/ops/movies/" + movie, null, ORIGIN, operator.csrfToken).status())
                .isIn(404, 405);
        assertThat(operator.post("/api/v1/ops/theaters", "{\"name\":\"x\"}").status()).isEqualTo(404);
        assertThat(operator.post("/api/v1/ops/seats", "{}").status()).isEqualTo(404);
        assertThat(operator.post("/api/v1/ops/members/1/role", "{\"role\":\"OPERATOR\"}").status()).isEqualTo(404);
        assertThat(count("movies")).isEqualTo(2);

        // 입력 오류는 400 {code,message}이며 아무것도 쓰지 않는다.
        String beforeInvalid = dataSnapshot();
        Map<String, String> invalidMovies = Map.of(
                "{\"title\":\"\",\"runtimeMinutes\":90,\"ageRating\":\"ALL\"}", "INVALID_TITLE",
                "{\"title\":\"x\",\"runtimeMinutes\":601,\"ageRating\":\"ALL\"}", "INVALID_RUNTIME",
                "{\"title\":\"x\",\"runtimeMinutes\":90,\"ageRating\":\"AGE_19\"}", "INVALID_AGE_RATING",
                "{\"title\":\"x\",\"runtimeMinutes\":90,\"ageRating\":\"ALL\",\"version\":3}", "UNSUPPORTED_FIELD",
                "[]", "INVALID_REQUEST",
                "{\"title\":", "INVALID_REQUEST");
        for (Map.Entry<String, String> entry : invalidMovies.entrySet()) {
            Reply reply = operator.post("/api/v1/ops/movies", entry.getKey());
            assertThat(reply.status()).as(entry.getKey()).isEqualTo(400);
            assertThat(reply.keys("$")).containsExactlyInAnyOrder("code", "message");
            assertThat((String) reply.json("$.code")).as(entry.getKey()).isEqualTo(entry.getValue());
        }
        Reply badPrice = operator.post("/api/v1/ops/screenings",
                "{\"movieId\":" + movie + ",\"theaterId\":" + hall
                        + ",\"startsAt\":\"2031-10-03T01:00:00Z\",\"basePrice\":1000000.5}");
        assertThat((String) badPrice.json("$.code")).isEqualTo("INVALID_PRICE");
        Reply badStart = operator.post("/api/v1/ops/screenings",
                screeningBody(movie, hall, "2031-10-03T10:00:00+09:00", 1));
        assertThat((String) badStart.json("$.code")).isEqualTo("INVALID_STARTS_AT");
        assertThat(operator.patch("/api/v1/ops/screenings/01", "{\"version\":0,\"basePrice\":1}").status()).isEqualTo(400);
        assertThat(dataSnapshot()).isEqualTo(beforeInvalid);
    }

    // AC5 판매 잠금·수정 충돌 ------------------------------------------------------------------------------

    @Test
    void salesLockedScreeningRejectsEveryChangeAndDeletionWhileUnlockedCanBeDeleted() throws Exception {
        long movie = insertMovie("잠금 영화", 100, "ALL");
        long hall = insertTheater("잠금관", "A", 3);
        long otherHall = insertTheater("잠금 옆관", "A", 2);
        Browser operator = operator("lock_operator");
        long locked = ((Number) operator.post("/api/v1/ops/screenings",
                screeningBody(movie, hall, "2031-11-01T01:00:00Z", 12000)).json("$.id")).longValue();
        long unlocked = ((Number) operator.post("/api/v1/ops/screenings",
                screeningBody(movie, hall, "2031-11-01T06:00:00Z", 12000)).json("$.id")).longValue();
        // 최초 확보 시 기록되는 판매 잠금(HJ-005)을 fixture로 설정한다.
        jdbcTemplate.update("UPDATE screenings SET sales_locked_at = UTC_TIMESTAMP(6) WHERE id = ?", locked);
        String before = dataSnapshot();

        for (String change : new String[] {
                "{\"version\":0,\"startsAt\":\"2031-11-02T01:00:00Z\"}",
                "{\"version\":0,\"basePrice\":1}",
                "{\"version\":0,\"theaterId\":" + otherHall + "}",
                "{\"version\":0,\"basePrice\":12000}"}) {
            Reply reply = operator.patch("/api/v1/ops/screenings/" + locked, change);
            assertThat(reply.status()).as(change).isEqualTo(409);
            assertThat((String) reply.json("$.code")).isEqualTo("SALES_LOCKED");
        }
        Reply delete = operator.delete("/api/v1/ops/screenings/" + locked);
        assertThat(delete.status()).isEqualTo(409);
        assertThat((String) delete.json("$.code")).isEqualTo("SALES_LOCKED");
        assertThat(dataSnapshot()).as("locked screening, inventory and versions unchanged").isEqualTo(before);

        // 잠금 전 회차는 삭제되고 inventory도 함께 제거된다(cascade가 아니라 같은 트랜잭션의 명시 삭제).
        assertThat(operator.delete("/api/v1/ops/screenings/" + unlocked).status()).isEqualTo(204);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM screenings WHERE id = ?", Integer.class, unlocked))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM screening_seats WHERE screening_id = ?", Integer.class, unlocked)).isZero();
        Reply again = operator.delete("/api/v1/ops/screenings/" + unlocked);
        assertThat(again.status()).isEqualTo(404);
        assertThat((String) again.json("$.code")).isEqualTo("SCREENING_NOT_FOUND");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM screening_seats WHERE screening_id = ?", Integer.class, locked)).isEqualTo(3);
    }

    @Test
    void staleVersionUpdatesAreRejectedAndConcurrentSameVersionUpdatesAdmitOne() throws Exception {
        long hall = insertTheater("버전관", "A", 2);
        Browser operator = operator("version_operator");
        Reply created = operator.post("/api/v1/ops/movies",
                "{\"title\":\"버전 영화\",\"runtimeMinutes\":100,\"ageRating\":\"AGE_12\"}");
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.keys("$")).containsExactlyInAnyOrder("id", "title", "runtimeMinutes", "ageRating", "version");
        long movie = ((Number) created.json("$.id")).longValue();
        assertThat((Integer) created.json("$.version")).isZero();

        Reply firstEdit = operator.patch("/api/v1/ops/movies/" + movie, "{\"version\":0,\"title\":\"첫 수정\"}");
        assertThat(firstEdit.status()).isEqualTo(200);
        assertThat((Integer) firstEdit.json("$.version")).isEqualTo(1);
        assertThat((Integer) firstEdit.json("$.runtimeMinutes")).isEqualTo(100);
        Reply staleMovie = operator.patch("/api/v1/ops/movies/" + movie, "{\"version\":0,\"title\":\"늦은 수정\"}");
        assertThat(staleMovie.status()).isEqualTo(409);
        assertThat((String) staleMovie.json("$.code")).isEqualTo("VERSION_CONFLICT");
        assertThat(jdbcTemplate.queryForMap("SELECT title, version FROM movies WHERE id = ?", movie))
                .containsEntry("title", "첫 수정").containsEntry("version", 1L);
        Reply missingMovie = operator.patch("/api/v1/ops/movies/" + (movie + 1000), "{\"version\":0,\"title\":\"x\"}");
        assertThat(missingMovie.status()).isEqualTo(404);
        assertThat((String) missingMovie.json("$.code")).isEqualTo("MOVIE_NOT_FOUND");

        long screening = ((Number) operator.post("/api/v1/ops/screenings",
                screeningBody(movie, hall, "2031-12-01T01:00:00Z", 10000)).json("$.id")).longValue();
        Reply priceEdit = operator.patch("/api/v1/ops/screenings/" + screening, "{\"version\":0,\"basePrice\":11000}");
        assertThat(priceEdit.status()).isEqualTo(200);
        assertThat((Integer) priceEdit.json("$.version")).isEqualTo(1);
        Reply staleScreening = operator.patch("/api/v1/ops/screenings/" + screening,
                "{\"version\":0,\"basePrice\":99999}");
        assertThat(staleScreening.status()).isEqualTo(409);
        assertThat((String) staleScreening.json("$.code")).isEqualTo("VERSION_CONFLICT");
        assertThat(jdbcTemplate.queryForMap("SELECT base_price, version FROM screenings WHERE id = ?", screening))
                .containsEntry("base_price", 11000L).containsEntry("version", 1L);

        // 같은 version으로 동시에 수정하면 정확히 하나만 반영된다.
        List<ConcurrentRunner.BarrierTask<Reply>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String body = "{\"version\":1,\"basePrice\":" + (20000 + i) + "}";
            tasks.add(gate -> {
                Browser mine = operator.copy();
                gate.await();
                return mine.patch("/api/v1/ops/screenings/" + screening, body);
            });
        }
        List<Reply> replies = ConcurrentRunner.runWithGate(CONCURRENCY_TIMEOUT, tasks);
        List<Integer> statuses = replies.stream().map(Reply::status).toList();
        assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(3);
        int winner = statuses.indexOf(200);
        assertThat(jdbcTemplate.queryForMap("SELECT base_price, version FROM screenings WHERE id = ?", screening))
                .containsEntry("base_price", 20000L + winner).containsEntry("version", 2L);
        System.out.println("[HJ-004] concurrent same-version screening updates statuses=" + statuses);
    }

    @Test
    void movieUpdateNeverRecalculatesExistingScreeningTimes() throws Exception {
        long hall = insertTheater("시간 보존관", "A", 2);
        long otherHall = insertTheater("시간 보존 옆관", "A", 2);
        Browser operator = operator("time_operator");
        long movie = ((Number) operator.post("/api/v1/ops/movies",
                "{\"title\":\"시간 영화\",\"runtimeMinutes\":120,\"ageRating\":\"ALL\"}").json("$.id")).longValue();
        long open = ((Number) operator.post("/api/v1/ops/screenings",
                screeningBody(movie, hall, "2032-01-10T01:00:00Z", 10000)).json("$.id")).longValue();
        long locked = ((Number) operator.post("/api/v1/ops/screenings",
                screeningBody(movie, otherHall, "2032-01-10T01:00:00Z", 10000)).json("$.id")).longValue();
        jdbcTemplate.update("UPDATE screenings SET sales_locked_at = UTC_TIMESTAMP(6) WHERE id = ?", locked);
        String timesBefore = screeningTimes();
        String publicBefore = new Browser().get("/api/v1/screenings?movieId=" + movie + "&date=2032-01-10").body();

        Reply edit = operator.patch("/api/v1/ops/movies/" + movie,
                "{\"version\":0,\"runtimeMinutes\":90,\"title\":\"시간 영화 (수정)\",\"ageRating\":\"AGE_12\"}");
        assertThat(edit.status()).isEqualTo(200);

        assertThat(screeningTimes()).as("stored screening times unchanged by movie update").isEqualTo(timesBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT ends_at FROM screenings WHERE id = ?", LocalDateTime.class, locked))
                .isEqualTo(LocalDateTime.parse("2032-01-10T03:00:00"));
        assertThat(new Browser().get("/api/v1/screenings?movieId=" + movie + "&date=2032-01-10").body())
                .isEqualTo(publicBefore);
        Reply detail = new Browser().get("/api/v1/movies/" + movie);
        assertThat(detail.keys("$")).containsExactlyInAnyOrder("id", "title", "runtimeMinutes", "ageRating");
        assertThat((Integer) detail.json("$.runtimeMinutes")).isEqualTo(90);
        assertThat((String) detail.json("$.ageRating")).isEqualTo("AGE_12");

        // 판매 잠금 회차는 시작 변경도 거부되어 종료 시각이 그대로다.
        Reply lockedMove = operator.patch("/api/v1/ops/screenings/" + locked,
                "{\"version\":0,\"startsAt\":\"2032-01-11T01:00:00Z\"}");
        assertThat(lockedMove.status()).isEqualTo(409);
        assertThat(screeningTimes()).isEqualTo(timesBefore);
        // 잠금 전 회차의 시작을 명시적으로 바꾸면 그 시점의 상영시간(90분)으로 종료를 계산한다.
        Reply openMove = operator.patch("/api/v1/ops/screenings/" + open,
                "{\"version\":0,\"startsAt\":\"2032-01-11T01:00:00Z\"}");
        assertThat(openMove.status()).isEqualTo(200);
        assertThat((String) openMove.json("$.endsAt")).isEqualTo("2032-01-11T02:30:00Z");
        // 새 회차도 수정된 상영시간을 쓴다.
        Reply fresh = operator.post("/api/v1/ops/screenings", screeningBody(movie, hall, "2032-01-12T01:00:00Z", 1));
        assertThat((String) fresh.json("$.endsAt")).isEqualTo("2032-01-12T02:30:00Z");
        assertThat(jdbcTemplate.queryForObject("SELECT ends_at FROM screenings WHERE id = ?", LocalDateTime.class, locked))
                .isEqualTo(LocalDateTime.parse("2032-01-10T03:00:00"));

        // 시작 경계는 필요한 잠금을 모두 얻은 뒤의 DB 시각으로 판정한다: 영화 행 잠금을 기다리는 동안 요청한 시작 시각이
        // 지나가면, 요청 시점에는 미래였더라도 거부된다.
        assertMovieLockWaitPastStartIsRejected(operator, movie, open);
    }

    /**
     * 독립 연결이 영화 행을 FOR UPDATE로 잡은 상태에서 회차 시작 변경(요청 시점에는 미래)을 보낸다. 요청이 영화 공유 잠금에서
     * 대기 중임을 processlist로 확인하고, DB 시각이 요청 시작을 지난 것을 확인한 뒤 잠금을 푼다. 임의 sleep 없이 조건을 짧게
     * 재확인하며 모든 대기는 유한하다.
     */
    private void assertMovieLockWaitPastStartIsRejected(Browser operator, long movie, long screening) throws Exception {
        String before = dataSnapshot();
        long version = jdbcTemplate.queryForObject("SELECT version FROM screenings WHERE id = ?", Long.class, screening);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM movies WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, movie);
                lock.executeQuery().close();
            }
            Instant requestedStart = dbNow().plusSeconds(2);
            String body = "{\"version\":" + version + ",\"startsAt\":\"" + requestedStart + "\"}";
            java.util.concurrent.Future<Reply> pending = executor.submit(
                    () -> operator.copy().patch("/api/v1/ops/screenings/" + screening, body));

            Instant deadline = Instant.now().plusSeconds(20);
            while (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.PROCESSLIST "
                    + "WHERE ID <> CONNECTION_ID() AND INFO LIKE 'SELECT runtime_minutes FROM movies WHERE id = %FOR SHARE'",
                    Integer.class) == 0) {
                assertThat(pending.isDone()).as("request must wait on the movie row lock").isFalse();
                assertThat(Instant.now()).as("request reached the movie lock wait").isBefore(deadline);
                Thread.onSpinWait();
            }
            while (!dbNow().isAfter(requestedStart)) {
                assertThat(Instant.now()).as("DB time passed the requested start").isBefore(deadline);
                assertThat(pending.isDone()).as("still waiting on the movie row lock").isFalse();
                Thread.onSpinWait();
            }
            holder.rollback();

            Reply reply = pending.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(reply.status()).as(reply.body()).isEqualTo(400);
            assertThat((String) reply.json("$.code")).isEqualTo("STARTS_AT_NOT_FUTURE");
        } finally {
            executor.shutdownNow();
        }
        assertThat(dataSnapshot()).as("lock-waited past-start change writes nothing").isEqualTo(before);
    }

    // helpers -----------------------------------------------------------------------------------------

    private void clean() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            TestDataCleaner.cleanAll(connection);
        }
    }

    private void applySeed() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            TestDatabaseGuard.requireTestSchema(connection.getCatalog());
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ClassPathResource(SEED_SCRIPT), StandardCharsets.UTF_8));
        }
    }

    private Map<String, List<Map<String, Object>>> seedSnapshot() {
        return Map.of(
                "movies", jdbcTemplate.queryForList("SELECT id, title, runtime_minutes, age_rating, version FROM movies ORDER BY id"),
                "theaters", jdbcTemplate.queryForList("SELECT id, name FROM theaters ORDER BY id"),
                "seats", jdbcTemplate.queryForList(
                        "SELECT id, theater_id, row_label, seat_number FROM seats ORDER BY id"));
    }

    private String dataSnapshot() {
        return jdbcTemplate.queryForList("SELECT id, title, runtime_minutes, age_rating, version FROM movies ORDER BY id")
                + "|" + jdbcTemplate.queryForList("SELECT * FROM screenings ORDER BY id")
                + "|" + jdbcTemplate.queryForList("SELECT * FROM screening_seats ORDER BY screening_id, seat_id");
    }

    private String screeningTimes() {
        return jdbcTemplate.queryForList("SELECT id, starts_at, ends_at FROM screenings ORDER BY id").toString();
    }

    private void assertNoOverlapInDatabase(long theaterId) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM screenings a JOIN screenings b ON a.theater_id = b.theater_id AND a.id < b.id "
                        + "WHERE a.theater_id = ? AND a.starts_at < DATE_ADD(b.ends_at, INTERVAL 10 MINUTE) "
                        + "AND b.starts_at < DATE_ADD(a.ends_at, INTERVAL 10 MINUTE)", Integer.class, theaterId))
                .isZero();
    }

    private void assertRejected(String sql, Object... args) {
        assertThatThrownBy(() -> jdbcTemplate.update(sql, args)).as(sql).isInstanceOf(DataAccessException.class);
    }

    private int screeningCount(long theaterId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM screenings WHERE theater_id = ?", Integer.class,
                theaterId);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Integer.class);
    }

    private Instant dbNow() {
        return ScreeningSchedule.fromDb(jdbcTemplate.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class));
    }

    private long insertMovie(String title, int runtime, String ageRating) {
        return insert("INSERT INTO movies (title, runtime_minutes, age_rating) VALUES (?, ?, ?)", title, runtime,
                ageRating);
    }

    /** 상영관과 단일 행 좌석(rowLabel 1..seats)을 만든다. rowLabel이 null이면 좌석 없이 만든다. */
    private long insertTheater(String name, String rowLabel, int seats) {
        long id = insert("INSERT INTO theaters (name) VALUES (?)", name);
        for (int number = 1; rowLabel != null && number <= seats; number++) {
            insertSeat(id, rowLabel, number);
        }
        return id;
    }

    private long insertSeat(long theaterId, String row, int number) {
        return insert("INSERT INTO seats (theater_id, row_label, seat_number) VALUES (?, ?, ?)", theaterId, row, number);
    }

    /** 운영 API를 거치지 않는 fixture(과거 시각 등). inventory도 함께 만든다. */
    private long insertScreening(long movieId, long theaterId, String startsAt, int runtime, long price) {
        Instant start = Instant.parse(startsAt);
        long id = insert("INSERT INTO screenings (movie_id, theater_id, starts_at, ends_at, base_price) "
                        + "VALUES (?, ?, ?, ?, ?)", movieId, theaterId, ScreeningSchedule.toDb(start),
                ScreeningSchedule.toDb(ScreeningSchedule.endsAt(start, runtime)), price);
        jdbcTemplate.update("INSERT INTO screening_seats (screening_id, theater_id, seat_id, state) "
                + "SELECT ?, theater_id, id, 'AVAILABLE' FROM seats WHERE theater_id = ?", id, theaterId);
        return id;
    }

    private long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private static String screeningBody(long movieId, long theaterId, String startsAt, long price) {
        return "{\"movieId\":" + movieId + ",\"theaterId\":" + theaterId + ",\"startsAt\":\"" + startsAt
                + "\",\"basePrice\":" + price + "}";
    }

    private long signup(String loginId) throws Exception {
        Reply reply = newBrowserWithCsrf().post("/api/v1/auth/signup",
                "{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(reply.status()).as("signup %s", loginId).isEqualTo(201);
        return ((Number) reply.json("$.memberId")).longValue();
    }

    private Browser login(String loginId) throws Exception {
        Browser browser = newBrowserWithCsrf();
        assertThat(browser.post("/api/v1/auth/login",
                "{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}").status()).isEqualTo(200);
        browser.fetchCsrf(); // 로그인으로 교체된 토큰을 다시 받는다.
        return browser;
    }

    /** 격리 테스트 DB에서만 역할을 바꾼 운영자 fixture. 공개 가입·API로는 운영자가 될 수 없다. */
    private Browser operator(String loginId) throws Exception {
        long memberId = signup(loginId);
        jdbcTemplate.update("UPDATE members SET role = 'OPERATOR' WHERE id = ?", memberId);
        return login(loginId);
    }

    private Browser newBrowserWithCsrf() throws Exception {
        Browser browser = new Browser();
        browser.fetchCsrf();
        return browser;
    }

    private record Reply(int status, String body, Map<String, List<String>> headers) {
        Object json(String path) {
            return JsonPath.read(body, path);
        }

        List<String> keys(String path) {
            Map<String, Object> object = JsonPath.read(body, path);
            return List.copyOf(object.keySet());
        }

        List<Long> longs(String path) {
            List<Number> values = JsonPath.read(body, path);
            return values.stream().map(Number::longValue).toList();
        }
    }

    /** 세션 쿠키 하나와 CSRF 토큰을 직접 관리하는 최소 HTTP 클라이언트. 동시 요청에는 {@link #copy()}를 쓴다. */
    private final class Browser {
        String sessionCookie;
        String csrfToken;
        String csrfHeader = "X-CSRF-TOKEN";

        Browser copy() {
            Browser copy = new Browser();
            copy.sessionCookie = sessionCookie;
            copy.csrfToken = csrfToken;
            copy.csrfHeader = csrfHeader;
            return copy;
        }

        void fetchCsrf() throws IOException, InterruptedException {
            Reply reply = get("/api/v1/auth/csrf");
            assertThat(reply.status()).isEqualTo(200);
            csrfHeader = (String) reply.json("$.headerName");
            csrfToken = (String) reply.json("$.token");
        }

        Reply get(String path) throws IOException, InterruptedException {
            return send("GET", path, null, null, null);
        }

        Reply post(String path, String json) throws IOException, InterruptedException {
            return send("POST", path, json, ORIGIN, csrfToken);
        }

        Reply patch(String path, String json) throws IOException, InterruptedException {
            return send("PATCH", path, json, ORIGIN, csrfToken);
        }

        Reply delete(String path) throws IOException, InterruptedException {
            return send("DELETE", path, null, ORIGIN, csrfToken);
        }

        Reply send(String method, String path, String json, String origin, String token)
                throws IOException, InterruptedException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(HTTP_TIMEOUT)
                    .method(method, json == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            if (json != null) {
                builder.header("Content-Type", "application/json");
            }
            if (origin != null) {
                builder.header("Origin", origin);
            }
            if (token != null) {
                builder.header(csrfHeader, token);
            }
            if (sessionCookie != null) {
                builder.header("Cookie", COOKIE + "=" + sessionCookie);
            }
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            for (String setCookie : response.headers().allValues("Set-Cookie")) {
                if (setCookie.startsWith(COOKIE + "=")) {
                    String value = setCookie.substring(COOKIE.length() + 1).split(";", 2)[0];
                    sessionCookie = value.isEmpty() || setCookie.contains("Max-Age=0") ? null : value;
                }
            }
            return new Reply(response.statusCode(), response.body(), response.headers().map());
        }
    }
}
