package com.smit.flightops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits on what an HTTP request may carry, bound from {@code app.http.*}.
 *
 * @param maxBodyBytes the largest request body read, in bytes (16384). A larger one gets
 *                     413 {@code PAYLOAD_TOO_LARGE} before it is parsed; see
 *                     {@code RequestBodyLimitFilter}. A booking with every field at
 *                     its longest and every character escaped is under 4 KB.
 */
@ConfigurationProperties(prefix = "app.http")
public record HttpProperties(@DefaultValue("16384") long maxBodyBytes) {

    public HttpProperties {
        // Zero would refuse every body, and every write with it, while reads stay healthy.
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("app.http.max-body-bytes must be positive");
        }
    }
}
