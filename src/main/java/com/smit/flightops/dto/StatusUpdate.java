package com.smit.flightops.dto;

import com.smit.flightops.entity.FlightStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdScalarDeserializer;

/**
 * The body of {@code PATCH /api/v1/flights/{flightNumber}/status}. The enum is
 * published as one named component, the one {@link FlightDto#status()} refers to.
 * Any other field, {@code status} sent twice, or a status that is not a constant's
 * exact name, is 400 {@code MALFORMED_REQUEST}.
 */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record StatusUpdate(@NotNull @Schema(enumAsRef = true) @JsonDeserialize(using = StatusUpdate.ExactName.class)
                           FlightStatus status) {

    /**
     * Reads a {@link FlightStatus} from its exact name and nothing else. Jackson's own
     * enum reader retries a name it does not know with the value trimmed, so
     * {@code " DELAYED"}, or {@code CANCELLED} with a NUL before it and a tab after it,
     * was applied as that status, while a proxy or a log in front of the service saw a
     * value no status has. No Jackson setting turns that retry off.
     *
     * <p>{@link FlightStatus#valueOf} matches the name exactly. Anything it refuses, and
     * any token that is not a string, a number included, is a 400
     * {@code MALFORMED_REQUEST}.
     */
    public static final class ExactName extends StdScalarDeserializer<FlightStatus> {

        public ExactName() {
            super(FlightStatus.class);
        }

        @Override
        public FlightStatus deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (FlightStatus) context.handleUnexpectedToken(FlightStatus.class, parser);
            }
            String text = parser.getString();
            try {
                return FlightStatus.valueOf(text);
            } catch (IllegalArgumentException e) {
                return (FlightStatus) context.handleWeirdStringValue(FlightStatus.class, text,
                        "expected the exact name of a FlightStatus constant");
            }
        }
    }
}
