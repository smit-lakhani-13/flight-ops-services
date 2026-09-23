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
 * to UTC. Anything it refuses is a 400 {@code MALFORMED_REQUEST}.
 */
public final class IsoInstantDeserializer extends StdScalarDeserializer<Instant> {

    public IsoInstantDeserializer() {
        super(Instant.class);
    }

    @Override
    public Instant deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return (Instant) context.handleUnexpectedToken(Instant.class, parser);
        }
        String text = parser.getString();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return (Instant) context.handleWeirdStringValue(Instant.class, text,
                    "expected an ISO-8601 instant such as 2026-09-23T10:00:00Z");
        }
    }
}
