package com.smit.flightops;

import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The keep-alive timeout the running Tomcat was given, read from its connector, so the
 * test sees what {@code server.tomcat.keep-alive-timeout} became rather than the
 * property's text.
 *
 * <p>Its own H2 database, so it shares no rows with other contexts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:tomcatkeepalive;DB_CLOSE_DELAY=-1")
class TomcatKeepAliveTest {

    /** The idle timeout an ALB has unless its attributes change it. */
    private static final int ALB_IDLE_TIMEOUT_MILLIS = 60_000;

    @Autowired private WebServerApplicationContext context;

    @Test
    @DisplayName("Tomcat keeps an idle connection for 75 s, longer than the load balancer does")
    void keepAliveOutlastsTheLoadBalancersIdleTimeout() {
        AbstractHttp11Protocol<?> protocol = (AbstractHttp11Protocol<?>)
                ((TomcatWebServer) context.getWebServer()).getTomcat().getConnector().getProtocolHandler();

        assertThat(protocol.getKeepAliveTimeout())
                .isEqualTo(75_000)
                .isGreaterThan(ALB_IDLE_TIMEOUT_MILLIS);
    }
}
