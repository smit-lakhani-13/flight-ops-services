package com.smit.flightops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Which probe a database failure reaches. Every replica shares the database, and each
 * pod's own pool carries the same load, so a db component in readiness would withdraw
 * every pod at once when either fails, and the load balancer would have no target
 * left. The db indicator here is
 * DOWN, named {@code dbHealthIndicator} so Boot's own backs off: {@code /actuator/health}
 * reports it for the alert, and the two probe groups do not include it. Its own H2
 * database keeps its schema out of the other contexts' tables.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:healthgroupstest;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(HealthGroupsTest.DatabaseDown.class)
class HealthGroupsTest {

    /** Matches the {noop} default in application.yml's default profile. */
    private static final String OPS_USER = "ops";
    private static final String OPS_PASSWORD = "dev-ops";

    @TestConfiguration
    static class DatabaseDown {
        @Bean
        HealthIndicator dbHealthIndicator() {
            return () -> Health.down().withDetail("error", "connection refused").build();
        }
    }

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("with the database DOWN, readiness stays 200 UP and the pod stays in the Service")
    void readinessLeavesTheDatabaseOut() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("with the database DOWN, liveness stays 200 UP and nothing restarts")
    void livenessLeavesTheDatabaseOut() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("with the database DOWN, /actuator/health is 503 DOWN, so an alert on it fires")
    void rootHealthReportsTheDatabase() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
    }

    /**
     * The operator's view says why: db is the DOWN component under the root, and the
     * readiness group has no db component at all, so its UP says nothing about the
     * database.
     */
    @Test
    @DisplayName("an operator sees db DOWN under /actuator/health and no db component in readiness")
    void operatorSeesWhichComponentIsDown() throws Exception {
        mockMvc.perform(get("/actuator/health").with(httpBasic(OPS_USER, OPS_PASSWORD)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.components.db.status").value("DOWN"));
        mockMvc.perform(get("/actuator/health/readiness").with(httpBasic(OPS_USER, OPS_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.readinessState.status").value("UP"))
                .andExpect(jsonPath("$.components.db").doesNotExist());
    }
}
