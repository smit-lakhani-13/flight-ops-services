package com.smit.flightops.config;

import com.smit.flightops.FlightOpsServiceApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EmbeddedDatabaseGuard} refusing the base document's in-memory H2 when
 * {@code DB_URL} says a real database was meant. A profile name no document matches,
 * such as {@code Prod}, would otherwise start each pod on its own H2 and pass readiness.
 */
class EmbeddedDatabaseGuardTest {

    private static final String DB_URL = "DB_URL=jdbc:postgresql://db.invalid:5432/flightops";
    private static final String IN_MEMORY_H2 = "spring.datasource.url=jdbc:h2:mem:flightops;DB_CLOSE_DELAY=-1";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EmbeddedDatabaseGuard.class);

    /** Profile names are case-sensitive, so {@code Prod} activates no document. */
    @Test
    @DisplayName("DB_URL with in-memory H2 stops startup, naming the active profiles and the fix")
    void dbUrlWithInMemoryH2StopsStartup() {
        runner.withPropertyValues(DB_URL, IN_MEMORY_H2, "spring.profiles.active=Prod")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("DB_URL is set")
                            .hasMessageContaining("(active profiles: Prod)")
                            .hasMessageContaining("SPRING_PROFILES_ACTIVE must include prod or postgres");
                });
    }

    /** The caveat: a developer with DB_URL exported gets a refusal that says what to unset. */
    @Test
    @DisplayName("DB_URL left set on a laptop stops a default-profile run, and says to unset it")
    void dbUrlWithNoProfileStopsStartup() {
        runner.withPropertyValues(DB_URL, IN_MEMORY_H2)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("(active profiles: none)")
                            .hasMessageContaining("To run on H2, unset DB_URL");
                });
    }

    /** What the prod and postgres documents do with DB_URL. */
    @Test
    @DisplayName("DB_URL with the PostgreSQL datasource it names starts")
    void dbUrlWithPostgresStarts() {
        runner.withPropertyValues(DB_URL, "spring.datasource.url=jdbc:postgresql://db.invalid:5432/flightops",
                        "spring.profiles.active=prod")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** A laptop run and the H2 tests: no DB_URL, or a blank one, is not a request for a real database. */
    @Test
    @DisplayName("in-memory H2 without DB_URL starts, as on a laptop and in the tests")
    void inMemoryH2WithoutDbUrlStarts() {
        runner.withPropertyValues(IN_MEMORY_H2)
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues(IN_MEMORY_H2, "DB_URL=")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * With no DB_URL the guard never reads the URL, so a {@code prod} run's unresolved
     * {@code ${DB_URL}} still reaches the datasource, which fails as CI's image check expects.
     */
    @Test
    @DisplayName("the prod profile with no DB_URL gets past the guard to the datasource's own failure")
    void prodWithoutDbUrlGetsPastTheGuard() {
        runner.withPropertyValues("spring.datasource.url=${DB_URL}", "spring.profiles.active=prod")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * The whole application, so the guard is shown to be picked up by the component scan.
     * The settings go in as arguments because {@code application.yml} outranks builder
     * defaults; the database name is this test's own, as in {@code EventPropertiesTest}.
     */
    @Test
    @DisplayName("the application with DB_URL set and no profile refuses to start")
    void theApplicationRefusesToStart() {
        SpringApplicationBuilder app = new SpringApplicationBuilder(FlightOpsServiceApplication.class)
                .web(WebApplicationType.NONE);

        assertThatThrownBy(() -> {
            try (var context = app.run(
                    "--DB_URL=jdbc:postgresql://db.invalid:5432/flightops",
                    "--spring.datasource.url=jdbc:h2:mem:embeddeddatabaseguard;DB_CLOSE_DELAY=-1",
                    "--spring.main.banner-mode=off")) {
                assertThat(context.isActive()).isFalse();
            }
        })
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_URL is set")
                .hasMessageContaining("SPRING_PROFILES_ACTIVE must include prod or postgres");
    }
}
