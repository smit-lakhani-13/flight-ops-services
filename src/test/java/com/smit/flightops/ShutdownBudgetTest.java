package com.smit.flightops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.LifecycleProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shutdown budget a rolling deploy rests on. On SIGTERM the pod's preStop
 * sleeps while the load balancer deregisters it, graceful shutdown then lets
 * in-flight requests finish, and the kubelet kills the pod once
 * {@code terminationGracePeriodSeconds} has passed. {@code application.yml} pins
 * the drain because the manifest does arithmetic on it. This reads the values
 * the running context bound, and the numbers in the manifests, so a longer
 * drain, a longer sleep, a longer deregistration delay or a shorter grace period
 * fails here and not mid-deploy.
 *
 * <p>The same properties as {@link TomcatKeepAliveTest}, so the two share one context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:tomcatkeepalive;DB_CLOSE_DELAY=-1")
class ShutdownBudgetTest {

    private static final Path DEPLOYMENT = Path.of("deploy/k8s/base/deployment.yaml");
    private static final Path INGRESS = Path.of("deploy/k8s/components/ingress/ingress.yaml");
    private static final String DEREGISTRATION_DELAY = "deregistration_delay.timeout_seconds=";

    @Autowired private ServerProperties server;
    @Autowired private LifecycleProperties lifecycle;

    @Test
    @DisplayName("shutdown is graceful, and a shutdown phase gets 30 s to drain")
    void shutdownIsGracefulWithAThirtySecondDrain() {
        assertThat(server.getShutdown()).isEqualTo(Shutdown.GRACEFUL);
        assertThat(lifecycle.getTimeoutPerShutdownPhase()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("the preStop sleep and the drain end before the kubelet's grace period does")
    void preStopAndDrainFitInTheGracePeriod() throws IOException {
        Map<String, Object> pod = podSpec();
        long grace = ((Number) pod.get("terminationGracePeriodSeconds")).longValue();
        Map<String, Object> container = map(list(pod.get("containers")).stream()
                .filter(c -> "flight-ops".equals(map(c).get("name")))
                .findFirst().orElseThrow());
        Map<String, Object> exec = map(map(map(container.get("lifecycle")).get("preStop")).get("exec"));
        List<Object> command = list(exec.get("command"));

        assertThat(command).hasSize(2).first().isEqualTo("sleep");
        long sleep = Long.parseLong(String.valueOf(command.get(1)));
        long drain = lifecycle.getTimeoutPerShutdownPhase().toSeconds();

        assertThat(grace).isEqualTo(55);
        assertThat(sleep + drain).as("preStop %d s + drain %d s", sleep, drain).isLessThan(grace);
    }

    @Test
    @DisplayName("the load balancer's deregistration delay ends before the kubelet's grace period does")
    void deregistrationDelayFitsInTheGracePeriod() throws IOException {
        long grace = ((Number) podSpec().get("terminationGracePeriodSeconds")).longValue();
        String attributes = String.valueOf(map(map(document(INGRESS, "Ingress").get("metadata")).get("annotations"))
                .get("alb.ingress.kubernetes.io/target-group-attributes"));
        long delay = Arrays.stream(attributes.split(","))
                .map(String::trim)
                .filter(attribute -> attribute.startsWith(DEREGISTRATION_DELAY))
                .mapToLong(attribute -> Long.parseLong(attribute.substring(DEREGISTRATION_DELAY.length())))
                .findFirst().orElseThrow();

        assertThat(delay).as("deregistration delay").isEqualTo(30).isLessThan(grace);
    }

    private static Map<String, Object> podSpec() throws IOException {
        Map<String, Object> deployment = document(DEPLOYMENT, "Deployment");
        return map(map(map(deployment.get("spec")).get("template")).get("spec"));
    }

    private static Map<String, Object> document(Path file, String kind) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return StreamSupport.stream(new Yaml().loadAll(reader).spliterator(), false)
                    .map(ShutdownBudgetTest::map)
                    .filter(document -> kind.equals(document.get("kind")))
                    .findFirst().orElseThrow();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
