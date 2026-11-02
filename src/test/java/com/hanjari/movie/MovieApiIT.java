package com.hanjari.movie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 실제 MySQL 테스트 스키마에 대한 통합 테스트. DB 정보가 없거나 스키마가 다르면
 * 건너뛰지 않고 실패한다(기획안 완료 기준 8).
 *
 * <p>필요 환경변수: HANJARI_TEST_DB_URL, HANJARI_TEST_DB_USERNAME, HANJARI_TEST_DB_PASSWORD.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MovieApiIT {

    private static final String TEST_SCHEMA = "hanjari_test";
    private static final String DEV_SCHEMA = "hanjari_dev";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) throws SQLException {
        String url = requireEnv("HANJARI_TEST_DB_URL");
        String username = requireEnv("HANJARI_TEST_DB_USERNAME");
        String password = requireEnv("HANJARI_TEST_DB_PASSWORD");

        // Flyway가 마이그레이션을 적용하기 전에 연결된 스키마를 확인한다.
        String schema = currentSchema(url, username, password);
        if (!TEST_SCHEMA.equals(schema) || DEV_SCHEMA.equals(schema)) {
            throw new IllegalStateException(
                    "Refusing to run integration tests against schema '" + schema + "'; expected '" + TEST_SCHEMA + "'");
        }

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @BeforeEach
    void cleanMovies() {
        movieRepository.deleteAllInBatch();
    }

    @Test
    void returnsStoredMovieWithoutModifyingData() throws Exception {
        Movie saved = movieRepository.saveAndFlush(new Movie("한자리 테스트 영화", 120, AgeRating.AGE_12));
        List<Map<String, Object>> before = snapshot();

        mockMvc.perform(get("/api/v1/movies/{id}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.id").value(saved.getId()))
                .andExpect(jsonPath("$.title").value("한자리 테스트 영화"))
                .andExpect(jsonPath("$.runtimeMinutes").value(120))
                .andExpect(jsonPath("$.ageRating").value("AGE_12"));

        assertThat(snapshot()).isEqualTo(before);
        assertThat(before).singleElement().satisfies(row -> {
            assertThat(row.get("title")).isEqualTo("한자리 테스트 영화");
            assertThat(((Number) row.get("runtime_minutes")).intValue()).isEqualTo(120);
            assertThat(row.get("age_rating")).isEqualTo("AGE_12");
        });
    }

    @Test
    void returnsNotFoundForMissingMovie() throws Exception {
        Movie saved = movieRepository.saveAndFlush(new Movie("다른 영화", 90, AgeRating.ALL));

        mockMvc.perform(get("/api/v1/movies/{id}", saved.getId() + 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MOVIE_NOT_FOUND"));
    }

    @Test
    void returnsNotFoundForMaximumIdWithoutData() throws Exception {
        mockMvc.perform(get("/api/v1/movies/9223372036854775807"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MOVIE_NOT_FOUND"));
    }

    @Test
    void returnsBadRequestForInvalidId() throws Exception {
        mockMvc.perform(get("/api/v1/movies/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MOVIE_ID"));
    }

    private List<Map<String, Object>> snapshot() {
        return jdbcTemplate.queryForList("SELECT id, title, runtime_minutes, age_rating FROM movies ORDER BY id");
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Integration test database is not configured: " + name + " is missing");
        }
        return value;
    }

    private static String currentSchema(String url, String username, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT DATABASE()")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
