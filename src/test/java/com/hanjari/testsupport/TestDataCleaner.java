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

    /** FK 자식 테이블이 먼저 오도록 유지한다. */
    public static final List<String> TABLES_CHILD_FIRST = List.of("movies");

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
                if (!TABLES_CHILD_FIRST.contains(table) && !PRESERVED_TABLES.contains(table)) {
                    result.add(table);
                }
            }
        }
        return result;
    }
}
