package com.hanjari.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 실제 MySQL 통합 테스트용 접속 가드.
 *
 * <p>규칙(HJ-002 AC2):
 * <ul>
 *   <li>지정된 세 환경변수 이름만 읽는다. 값은 로그·예외 메시지에 남기지 않는다.</li>
 *   <li>누락·빈 값, 연결 실패, {@value #TEST_SCHEMA} 이외 스키마는 모두 예외로 실패한다. skip이나 H2 대체는 없다.</li>
 *   <li>Flyway·데이터 변경 전에 독립 연결로 {@code SELECT DATABASE()}를 확인한다.</li>
 * </ul>
 */
public final class TestDatabaseGuard {

    public static final String URL_ENV = "HANJARI_TEST_DB_URL";
    public static final String USERNAME_ENV = "HANJARI_TEST_DB_USERNAME";
    public static final String PASSWORD_ENV = "HANJARI_TEST_DB_PASSWORD";
    public static final List<String> ENV_NAMES = List.of(URL_ENV, USERNAME_ENV, PASSWORD_ENV);

    public static final String TEST_SCHEMA = "hanjari_test";

    private TestDatabaseGuard() {
    }

    /** 접속 정보. toString은 비밀번호와 URL 값을 노출하지 않는다. */
    public record Settings(String url, String username, String password) {
        @Override
        public String toString() {
            return "Settings[url=<" + URL_ENV + ">, username=<" + USERNAME_ENV + ">, password=<hidden>]";
        }
    }

    /** 시스템 환경변수에서 설정을 읽고 연결된 스키마를 확인한 뒤 반환한다. */
    public static Settings verifiedFromEnvironment() {
        Settings settings = fromEnvironment(System::getenv);
        requireTestSchema(connectedSchema(settings));
        return settings;
    }

    /** 지정된 환경변수 이름만 조회한다. 누락·빈 값이면 변수 이름만 포함한 예외로 실패한다. */
    public static Settings fromEnvironment(Function<String, String> env) {
        return new Settings(require(env, URL_ENV), require(env, USERNAME_ENV), require(env, PASSWORD_ENV));
    }

    /** 연결 스키마가 정확히 {@value #TEST_SCHEMA}가 아니면 실패한다. */
    public static void requireTestSchema(String schema) {
        if (!TEST_SCHEMA.equals(schema)) {
            throw new IllegalStateException(
                    "Refusing to run integration tests against schema '" + schema + "'; expected '" + TEST_SCHEMA + "'");
        }
    }

    /** 독립 JDBC 연결로 현재 스키마를 조회한다. 연결 실패는 비밀 값 없이 예외로 전달한다. */
    public static String connectedSchema(Settings settings) {
        try (Connection connection = openConnection(settings);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT DATABASE()")) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            // 드라이버 메시지에는 호스트 정보가 포함될 수 있으나 비밀번호는 포함되지 않는다. SQLState만 남긴다.
            throw new IllegalStateException(
                    "Integration test database connection failed (SQLState=" + e.getSQLState() + ")");
        }
    }

    /** 커넥션 풀을 거치지 않는 독립 연결을 연다. 호출자가 닫아야 한다. */
    public static Connection openConnection(Settings settings) throws SQLException {
        return DriverManager.getConnection(settings.url(), settings.username(), settings.password());
    }

    /**
     * 연결·읽기 timeout을 강제한 독립 연결을 연다(중단·세션 확인 같은 관리 작업용).
     * connectTimeout·socketTimeout 속성과 {@link Connection#setNetworkTimeout}을 함께 적용한다.
     * 호출자는 이와 별도로 전체 작업에 유한 deadline을 둬야 한다.
     */
    public static Connection openConnection(Settings settings, Duration ioTimeout) throws SQLException {
        int millis = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, ioTimeout.toMillis()));
        Properties properties = new Properties();
        properties.setProperty("user", settings.username());
        properties.setProperty("password", settings.password());
        properties.setProperty("connectTimeout", Integer.toString(millis));
        properties.setProperty("socketTimeout", Integer.toString(millis));
        Connection connection = DriverManager.getConnection(settings.url(), properties);
        try {
            connection.setNetworkTimeout(Runnable::run, millis);
        } catch (SQLException | RuntimeException e) {
            connection.abort(Runnable::run);
            throw e;
        }
        return connection;
    }

    /** 검증된 설정을 Spring 테스트 컨텍스트에 등록한다. 마이그레이션 위치는 운영 경로만 사용한다. */
    public static void register(DynamicPropertyRegistry registry, Settings settings) {
        registry.add("spring.datasource.url", settings::url);
        registry.add("spring.datasource.username", settings::username);
        registry.add("spring.datasource.password", settings::password);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    private static String require(Function<String, String> env, String name) {
        String value = env.apply(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Integration test database is not configured: " + name + " is missing");
        }
        return value;
    }
}
