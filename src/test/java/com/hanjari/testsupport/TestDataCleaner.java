package com.hanjari.testsupport;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 통합 테스트 데이터 정리 규칙(HJ-002 AC4).
 *
 * <ul>
 *   <li>각 테스트는 시작 전에 정리한다. 실패한 테스트의 데이터는 다음 테스트 시작 시 제거된다.</li>
 *   <li>{@link #TABLES_CHILD_FIRST}는 FK 자식 → 부모 순서다. 새 테이블을 추가하는 마이그레이션은 이 목록에
 *       참조 순서대로 등록해야 한다. 미등록 테이블은 {@link #unregisteredTables(Connection)} 검증에서 실패한다.</li>
 *   <li>권한 범위(DROP 없음)와 FK 안전성을 위해 TRUNCATE·DROP·FK 검사 비활성화를 쓰지 않고 DELETE만 사용한다.</li>
 *   <li>Flyway 이력 테이블은 지우지 않는다.</li>
 *   <li>정리 전 연결 스키마가 hanjari_test인지 다시 확인한다.</li>
 * </ul>
 */
public final class TestDataCleaner {

    /**
     * FK 자식 테이블이 먼저 오도록 유지한다. HJ-003: Spring Session 테이블(속성 → 세션)과 members 추가.
     * Spring Session 테이블 이름은 대문자다. lower_case_table_names 설정에 따라 information_schema가 소문자로 보고할 수 있어
     * 등록 여부는 대소문자를 구분하지 않고 비교한다.
     */
    public static final List<String> TABLES_CHILD_FIRST = List.of(
            // HJ-006: 확보 좌석(→ 확보·inventory). inventory(→ 확보)가 먼저 지워진 뒤 확보(→ 회원·회차)를 지운다.
            "hold_items",
            // HJ-004: inventory → (HJ-006 확보) → 회차(→ 영화·상영관) → 좌석 → 상영관
            "screening_seats", "holds", "screenings", "seats", "theaters",
            // HJ-005: 멱등 기록(→ members)
            "idempotency_records",
            "SPRING_SESSION_ATTRIBUTES", "SPRING_SESSION", "members", "movies");

    /** 정리 대상에서 제외하는 관리 테이블. */
    public static final Set<String> PRESERVED_TABLES = Set.of("flyway_schema_history");

    private TestDataCleaner() {
    }

    public static void cleanAll(Connection connection) throws SQLException {
        TestDatabaseGuard.requireTestSchema(connection.getCatalog());
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String table : TABLES_CHILD_FIRST) {
                statement.executeUpdate("DELETE FROM `" + table + "`");
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    /** 스키마에 존재하지만 정리 목록·보존 목록 어디에도 없는 테이블. 비어 있어야 한다. */
    public static Set<String> unregisteredTables(Connection connection) throws SQLException {
        Set<String> result = new TreeSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT table_name FROM information_schema.tables "
                             + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'")) {
            while (rs.next()) {
                String table = rs.getString(1);
                if (!containsIgnoreCase(TABLES_CHILD_FIRST, table) && !containsIgnoreCase(PRESERVED_TABLES, table)) {
                    result.add(table);
                }
            }
        }
        return result;
    }

    private static boolean containsIgnoreCase(java.util.Collection<String> names, String table) {
        return names.stream().anyMatch(name -> name.equalsIgnoreCase(table));
    }
}
