package com.smit.flightops.config;

import org.springframework.stereotype.Component;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.deser.jdk.StringDeserializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Every text field in a request body takes a JSON string. A number or a boolean
 * there is a 400 {@code MALFORMED_REQUEST}, as an array or an object already was.
 * Jackson's own reader turns {@code 123} into {@code "123"} and {@code true} into
 * {@code "true"}: {@code allow-coercion-of-scalars: false} in {@code application.yml}
 * stops a string becoming a number, not a number becoming a string. The OpenAPI
 * document types each of these fields as a string, so {@code "flightNumber": 123}
 * got a 201 that its own contract did not allow.
 *
 * <p>One rule for every {@code String} rather than an annotation on each field, so
 * a field added later cannot miss it; a field with its own {@code @JsonDeserialize}
 * keeps that reader. A {@code @Component} rather than a mapper customizer in a
 * {@code @Configuration}, because a {@code @WebMvcTest} slice registers every
 * {@link JacksonModule} bean and loads no {@code @Configuration}, so the controller
 * tests read a body the way the service does. The service reads JSON only from
 * request bodies.
 */
@Component
public class StrictTextModule extends SimpleModule {

    public StrictTextModule() {
        super(StrictTextModule.class.getSimpleName());
        addDeserializer(String.class, new StringsOnly());
    }

    /** Jackson's own reader for everything else, so a string, a null, an array and an object behave as before. */
    static final class StringsOnly extends StringDeserializer {

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            JsonToken token = parser.currentToken();
            if (token != null && (token.isNumeric() || token.isBoolean())) {
                return (String) context.handleUnexpectedToken(String.class, parser);
            }
            return super.deserialize(parser, context);
        }
    }
}
