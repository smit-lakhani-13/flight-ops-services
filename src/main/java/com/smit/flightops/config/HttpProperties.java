package com.smit.flightops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits on what an HTTP request may carry, bound from {@code app.http.*}.
 *
 * @param maxBodyBytes the largest request body read, in bytes (16384). A declared
 *                     {@code Content-Length} over it gets 413 {@code PAYLOAD_TOO_LARGE}
 *                     unread, and a body without one gets it once a read passes the
 *                     limit, so nothing parses more; a body nothing reads is never
 *                     counted. See {@code RequestBodyLimitFilter}. A booking with
 *                     every field at its longest and every character escaped is under
 *                     4 KB.
 */
@ConfigurationProperties(prefix = "app.http")
public record HttpProperties(@DefaultValue("16384") long maxBodyBytes) {

    public HttpProperties {
        // Zero would refuse every non-empty body, so both POSTs and the status PATCH,
        // while reads and the DELETEs stay healthy.
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("app.http.max-body-bytes must be positive");
        }
    }
}
