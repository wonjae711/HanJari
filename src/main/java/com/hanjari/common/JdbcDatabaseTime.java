package com.hanjari.common;

import com.hanjari.screening.ScreeningSchedule;
import java.time.Instant;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** DB {@code UTC_TIMESTAMP(6)}. 트랜잭션 안에서 호출하면 그 트랜잭션의 연결로 읽는다. */
@Component
public class JdbcDatabaseTime implements DatabaseTime {

    private final JdbcTemplate jdbcTemplate;

    public JdbcDatabaseTime(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Instant now() {
        return ScreeningSchedule.fromDb(jdbcTemplate.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class));
    }
}
