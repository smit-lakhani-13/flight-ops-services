package com.smit.flightops.validation;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdScalarDeserializer;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Reads an {@link Instant} from an ISO-8601 string and nothing else. Jackson's own
 * reader also takes a number, or a string of digits, as epoch seconds, so a client
 * sending epoch milliseconds would create a flight in the year 58971 that
 * {@code @Future} accepts. No Jackson setting turns that path off.
 *
 * <p>{@link Instant#parse} accepts an offset such as {@code +05:30} and converts it
 * to UTC. Anything it refuses is a 400 {@code MALFORMED_REQUEST}. So is a year
 * after 9999, which it accepts: PostgreSQL stores nothing after 294276 AD, and the
 * failed insert reached the client as a 409 that told it to retry.
 */
public final class IsoInstantDeserializer extends StdScalarDeserializer<Instant> {

    private static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999999Z");

    public IsoInstantDeserializer() {
        super(Instant.class);
    }

    @Override
    public Instant deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return (Instant) context.handleUnexpectedToken(Instant.class, parser);
        }
        String text = parser.getString();
        Instant value;
        try {
            value = Instant.parse(text);
        } catch (DateTimeParseException e) {
            return (Instant) context.handleWeirdStringValue(Instant.class, text,
                    "expected an ISO-8601 instant such as 2026-09-23T10:00:00Z");
        }
        if (value.isAfter(MAX)) {
            return (Instant) context.handleWeirdStringValue(Instant.class, text,
                    "expected an instant no later than 9999-12-31T23:59:59Z");
        }
        return value;
    }
}
