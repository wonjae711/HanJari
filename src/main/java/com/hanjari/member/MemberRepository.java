package com.hanjari.member;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** members 테이블 접근. 로그인 ID 고유성은 DB UNIQUE 제약이 최종 보장한다(동시 가입 포함). */
@Repository
public class MemberRepository {

    private static final String COLUMNS = "id, login_id, password_hash, role";

    private final JdbcTemplate jdbcTemplate;

    public MemberRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 새 회원을 저장하고 ID를 반환한다. 생성 시각은 DB UTC 시각이다.
     *
     * @throws org.springframework.dao.DuplicateKeyException 같은 로그인 ID가 이미 있을 때
     */
    public long insert(String loginId, String passwordHash, MemberRole role) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO members (login_id, password_hash, role, created_at) VALUES (?, ?, ?, UTC_TIMESTAMP(6))",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, loginId);
            statement.setString(2, passwordHash);
            statement.setString(3, role.name());
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Member> findByLoginId(String loginId) {
        List<Member> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM members WHERE login_id = ?", MemberRepository::map, loginId);
        return rows.stream().findFirst();
    }

    public Optional<Member> findById(long id) {
        List<Member> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM members WHERE id = ?", MemberRepository::map, id);
        return rows.stream().findFirst();
    }

    private static Member map(ResultSet rs, int rowNum) throws SQLException {
        return new Member(rs.getLong("id"), rs.getString("login_id"), rs.getString("password_hash"),
                MemberRole.valueOf(rs.getString("role")));
    }
}
