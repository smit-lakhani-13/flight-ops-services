package com.smit.flightops.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Refuses the base document's in-memory H2 when a real database was asked for. That
 * document is the laptop setup and applies under every profile; only the
 * {@code postgres} and {@code prod} documents replace its datasource. Spring accepts a
 * profile name no document matches, such as {@code Prod} (names are case-sensitive) or
 * {@code aws}, and each pod would then start on its own H2, seed the demo flights, pass
 * readiness and still publish every booking to the real queue.
 *
 * <p>{@code DB_URL} is the sign that a real database was meant: the ConfigMaps and
 * {@code compose.yaml} set it, and a laptop run on H2 and the H2 tests do not. The
 * guard is not keyed on {@code app.events.publisher}, because H2 with {@code sqs} is a
 * supported laptop setup. With no {@code DB_URL} it returns before reading the URL,
 * whose unresolved {@code ${DB_URL}} under {@code prod} would throw here, so a
 * {@code prod} run without one still fails on the datasource, with
 * {@code 'url' must start with "jdbc"}, as CI's image check expects. A developer with
 * {@code DB_URL} exported in the shell has default-profile runs and the H2 tests
 * refused, and the message says why.
 */
@Configuration
public class EmbeddedDatabaseGuard {

    private static final String IN_MEMORY_H2 = "jdbc:h2:mem:";

    /**
     * The bean holds nothing; initialising it is the check. It is a singleton, so a
     * refusal stops the context before the web server, the seeder or the outbox drain
     * starts.
     */
    @Bean
    InitializingBean embeddedDatabaseGuardCheck(Environment environment) {
        return () -> refuseInMemoryH2WithDbUrl(environment);
    }

    private static void refuseInMemoryH2WithDbUrl(Environment environment) {
        if (!StringUtils.hasText(environment.getProperty("DB_URL"))
                || !environment.getProperty("spring.datasource.url", "").startsWith(IN_MEMORY_H2)) {
            return;
        }
        String[] active = environment.getActiveProfiles();
        throw new IllegalStateException("DB_URL is set, but the datasource is still the laptop default, "
                + "in-memory H2 (active profiles: " + (active.length == 0 ? "none" : String.join(", ", active))
                + "). SPRING_PROFILES_ACTIVE must include prod or postgres, in lower case, for DB_URL "
                + "to be used. To run on H2, unset DB_URL");
    }
}
