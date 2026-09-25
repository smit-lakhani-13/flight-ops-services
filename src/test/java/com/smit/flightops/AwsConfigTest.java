package com.smit.flightops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The SDK module that {@code AwsConfig} needs for IRSA. On EKS the pod's AWS
 * identity is a token file. The step of {@code DefaultCredentialsProvider} that
 * reads it swaps the token for credentials by calling STS, and it loads that
 * code from the {@code sts} module by reflection. Without the module the step
 * fails and the chain moves on to the next source, so the service starts and
 * then every SQS send fails for want of the pod's role. No main code imports
 * the module, so the compiler cannot notice it going; this test can.
 */
class AwsConfigTest {

    @Test
    @DisplayName("the sts module is on the classpath, so the credential chain can use the IRSA token")
    void stsModuleIsOnTheClasspath() {
        assertThatCode(() -> Class.forName("software.amazon.awssdk.services.sts.StsClient"))
                .doesNotThrowAnyException();
    }
}
