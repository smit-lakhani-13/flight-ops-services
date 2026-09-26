package com.smit.flightops.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests on the rules both list endpoints share, with no Spring and no
 * database. The HTTP cases, an unknown property and an offset that overflows,
 * are in {@code ErrorContractTest}; this pins what the returned {@code Sort}
 * holds, which no response shows directly.
 *
 * <p>{@code Sort.Order#equals} compares the null handling too, so a plain
 * {@code Sort.Order.asc(...)} in {@code containsExactly} asserts that the order
 * carries none.
 */
class SortPolicyTest {

    private static final Set<String> SORTABLE =
            Set.of("id", "createdAt", "passengerName", "seats", "cancelledAt", "departureTime");
    private static final Set<String> TEXTUAL = Set.of("passengerName");
    private static final Set<String> NULLABLE = Set.of("cancelledAt");

    private static Sort stable(Sort requested) {
        return SortPolicy.stable(PageRequest.of(0, 20, requested), SORTABLE, TEXTUAL, NULLABLE).getSort();
    }

    @Test
    @DisplayName("a nullable property puts nulls last in both directions, whatever the request said")
    void nullablePropertyPutsNullsLastInBothDirections() {
        // NATIVE, as the pageable resolver binds ?sort=, and NULLS_FIRST, which only code can pass.
        for (Sort.Direction direction : Sort.Direction.values()) {
            for (Sort.NullHandling requested : List.of(Sort.NullHandling.NATIVE, Sort.NullHandling.NULLS_FIRST)) {
                Sort sort = stable(Sort.by(new Sort.Order(direction, "cancelledAt", requested)));

                assertThat(sort).as("%s %s", direction, requested).containsExactly(
                        new Sort.Order(direction, "cancelledAt").nullsLast(),
                        Sort.Order.asc("id"));
            }
        }
    }

    @Test
    @DisplayName("a NOT NULL property's order carries no null handling, whatever the request said")
    void notNullPropertyCarriesNoNullHandling() {
        Sort sort = stable(Sort.by(Sort.Order.desc("seats").nullsFirst(), Sort.Order.asc("createdAt").nullsLast()));

        assertThat(sort).containsExactly(
                Sort.Order.desc("seats"),
                Sort.Order.asc("createdAt"),
                Sort.Order.asc("id"));
        assertThat(sort).allSatisfy(order ->
                assertThat(order.getNullHandling()).isEqualTo(Sort.NullHandling.NATIVE));
    }

    @Test
    @DisplayName("the flight list's default order is departure time then id, both plainly ascending")
    void defaultFlightOrderMatchesTheIndex() {
        // What V11__flights_departure_time_index.sql indexes, column for column:
        // Hibernate renders this as ORDER BY departure_time, id.
        Sort sort = stable(Sort.by("departureTime"));

        assertThat(sort).containsExactly(Sort.Order.asc("departureTime"), Sort.Order.asc("id"));
    }

    @Test
    @DisplayName("a caller's own order on id is kept, not followed by a second one")
    void callersIdOrderIsTheTiebreaker() {
        // A plain DESC, which PostgreSQL can read backwards off the primary key.
        Sort sort = stable(Sort.by(Sort.Order.desc("id")));

        assertThat(sort).containsExactly(Sort.Order.desc("id"));
    }

    @Test
    @DisplayName("ignorecase survives the rebuild on text and is still dropped on a number")
    void ignoreCaseIsKeptOnTextOnly() {
        Pageable pageable = SortPolicy.stable(PageRequest.of(0, 20, Sort.by(
                        Sort.Order.asc("passengerName").ignoreCase(),
                        Sort.Order.desc("seats").ignoreCase())),
                SORTABLE, TEXTUAL, NULLABLE);

        assertThat(pageable.getSort()).containsExactly(
                Sort.Order.asc("passengerName").ignoreCase(),
                Sort.Order.desc("seats"),
                Sort.Order.asc("id"));
    }
}
