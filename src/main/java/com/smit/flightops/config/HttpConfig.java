package com.smit.flightops.config;

import com.smit.flightops.security.ErrorResponseWriter;
import com.smit.flightops.security.RequestBodyLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds {@link RequestBodyLimitFilter} from {@link HttpProperties}. A bean here rather
 * than a {@code @Component}, because a {@code @WebMvcTest} slice picks up every filter
 * component and loads no {@code @Configuration}, and the slices have neither the
 * properties nor the writer. Boot registers any {@code Filter} bean with the container,
 * in the order the class declares.
 */
@Configuration
public class HttpConfig {

    @Bean
    public RequestBodyLimitFilter requestBodyLimitFilter(HttpProperties properties, ErrorResponseWriter writer) {
        return new RequestBodyLimitFilter(properties.maxBodyBytes(), writer);
    }
}
