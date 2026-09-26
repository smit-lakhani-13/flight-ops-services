package com.smit.flightops;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pool each profile builds, read from {@code application.yml} as Boot resolves it:
 * profile documents over the base document, with no inheritance between profiles. Each
 * case loads the file for one profile, or none, and runs only Boot's DataSource
 * auto-configuration. Hikari opens no connection until one is borrowed, and nothing
 * here borrows one, so no database is needed.
 */
class DataSourceSettingsTest {

    private static final String DB_URL = "jdbc:postgresql://db.invalid:5432/flightops";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
            .withPropertyValues("DB_URL=" + DB_URL, "DB_USER=flightops", "DB_PASSWORD=unused");

    /**
     * A 5 s wait for a connection, so a saturated pool answers 503 DATABASE_UNAVAILABLE
     * before a caller gives up, and READ COMMITTED whatever the server's default, which
     * the flight lock's re-read of the idempotency key needs. The URL and the lock
     * timeout prove the profile's own document was read, not only the base one.
     */
    @ParameterizedTest
    @ValueSource(strings = {"postgres", "prod"})
    @DisplayName("each PostgreSQL profile pins a 5 s connection wait and READ COMMITTED on the pool")
    void postgresProfilesPinTheWaitAndTheIsolation(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile).run(context -> {
            HikariDataSource pool = context.getBean(HikariDataSource.class);

            assertThat(pool.getJdbcUrl()).isEqualTo(DB_URL);
            assertThat(pool.getConnectionInitSql()).isEqualTo("SET lock_timeout = '3s'");
            assertThat(pool.getConnectionTimeout()).isEqualTo(5_000L);
            assertThat(pool.getTransactionIsolation()).isEqualTo("TRANSACTION_READ_COMMITTED");
        });
    }

    /**
     * The base document keeps Hikari's 30 s wait, so the H2 concurrency tests, which share
     * its pool of ten, are unaffected by the PostgreSQL profiles' 5 s. READ COMMITTED comes
     * from the same base document the PostgreSQL profiles inherit it from. The H2 URL and
     * H2's lock timeout prove no profile document was read.
     */
    @Test
    @DisplayName("the default H2 profile keeps the 30 s connection wait and pins READ COMMITTED")
    void defaultProfileKeepsTheLongerWait() {
        runner.run(context -> {
            HikariDataSource pool = context.getBean(HikariDataSource.class);

            assertThat(pool.getJdbcUrl()).isEqualTo("jdbc:h2:mem:flightops;DB_CLOSE_DELAY=-1");
            assertThat(pool.getConnectionInitSql()).isEqualTo("SET LOCK_TIMEOUT 3000");
            assertThat(pool.getConnectionTimeout()).isEqualTo(30_000L);
            assertThat(pool.getTransactionIsolation()).isEqualTo("TRANSACTION_READ_COMMITTED");
        });
    }
}
