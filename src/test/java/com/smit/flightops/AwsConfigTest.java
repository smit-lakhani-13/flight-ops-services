package com.smit.flightops;

import com.smit.flightops.config.AwsConfig;
import com.smit.flightops.config.AwsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The SDK module that {@code AwsConfig} needs for IRSA. On EKS the pod's AWS
 * identity is a token file. The step of {@code DefaultCredentialsProvider} that
 * reads it swaps the token for credentials by calling STS, and it loads that
 * code from the {@code sts} module by reflection. Without the module the step
 * fails and the chain moves on to the next source, so the service starts and
 * then every SQS send fails for want of the pod's role. No main code imports
 * the module, so the compiler cannot notice it going; this test can.
 *
 * <p>It also builds the SQS client {@code AwsConfig} makes, which no other test
 * does: the ElasticMQ test builds its own, with an endpoint override.
 */
class AwsConfigTest {

    @Test
    @DisplayName("the sts module is on the classpath, so the credential chain can use the IRSA token")
    void stsModuleIsOnTheClasspath() {
        assertThatCode(() -> Class.forName("software.amazon.awssdk.services.sts.StsClient"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the SQS client bounds each attempt and the whole call, so a silent queue cannot hold a drain")
    void theSqsClientBoundsEachCall() {
        // Building it resolves no credentials; the chain runs on the first send.
        try (SqsClient client = new AwsConfig().sqsClient(new AwsProperties("eu-west-1", null))) {
            ClientOverrideConfiguration override = client.serviceClientConfiguration().overrideConfiguration();
            Duration call = override.apiCallTimeout().orElseThrow();
            Duration attempt = override.apiCallAttemptTimeout().orElseThrow();

            // An attempt shorter than the call leaves the retry policy room to try again.
            assertThat(attempt).isPositive().isLessThan(call);
            assertThat(call).as("AwsConfig's Javadoc prices a drain at the batch size times 5 seconds")
                    .isEqualTo(Duration.ofSeconds(5));
        }
    }
}
