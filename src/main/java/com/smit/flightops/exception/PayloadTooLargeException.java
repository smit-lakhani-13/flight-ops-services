package com.smit.flightops.exception;

import java.io.IOException;

/**
 * A request body passed {@code app.http.max-body-bytes} while it was being read. It is
 * an {@link IOException} because the stream {@code RequestBodyLimitFilter} hands on
 * throws it from {@code read}, where a reader expects only that type. The message is
 * written for the client, and both 413 paths answer with it.
 */
public class PayloadTooLargeException extends IOException {

    public PayloadTooLargeException(long maxBodyBytes) {
        super(messageFor(maxBodyBytes));
    }

    /** The one wording, for the 413 that is decided before anything is read too. */
    public static String messageFor(long maxBodyBytes) {
        return "The request body is larger than " + maxBodyBytes + " bytes.";
    }
}
