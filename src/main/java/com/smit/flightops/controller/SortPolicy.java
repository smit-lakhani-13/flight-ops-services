package com.smit.flightops.controller;

import com.smit.flightops.exception.UnknownSortPropertyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What both list endpoints need from a {@code Pageable}, in one place so they
 * cannot drift apart.
 *
 * <p>The sort property is checked against a list the endpoint publishes, not
 * against the entity: {@code ?sort=idempotencyKey} would otherwise leak keys a
 * comparison at a time. And {@code id} is appended as a tiebreaker, because a
 * non-unique {@code ORDER BY} lets page 0 and page 1 overlap or skip rows.
 *
 * <p>{@code ignorecase} is kept only on text. A declared {@code @Query} wraps the
 * column in {@code lower()} whatever its type, and Hibernate refuses that for a
 * number or a time, so {@code ?sort=seats,asc,ignorecase} was a 500.
 *
 * <p>Nulls go last on a property that can hold one, and only there. The
 * {@code sort} parameter cannot say where nulls go, so each database used its
 * own rule: H2 sorts NULL lowest and PostgreSQL highest, and
 * {@code ?sort=cancelledAt,asc} listed the active bookings first on H2 and
 * last on PostgreSQL. So an order on a property the endpoint lists as nullable
 * is NULLS LAST in both directions. Every other order, the tiebreaker
 * included, carries no null handling, whatever the request said. On a NOT NULL
 * column it would change no result, and on PostgreSQL it would cost an index:
 * DESC NULLS LAST is not the reverse of an ascending index, so
 * {@code ?sort=id,desc} could no longer read the primary key backwards. The
 * default flight order is then {@code departure_time, id}, both plainly
 * ascending, which PostgreSQL sorts NULLS LAST anyway: the order the index in
 * {@code V11__flights_departure_time_index.sql} holds.
 */
final class SortPolicy {

    private static final String TIEBREAKER = "id";

    private SortPolicy() {
    }

    /**
     * @param pageable the bound request, defaults already applied
     * @param sortable the properties this endpoint offers, entity-property spelling
     * @param textual  the subset that are strings, where {@code ignorecase} means
     *                 something; it is dropped on the others
     * @param nullable the subset whose column can be null, where nulls sort last
     *                 in both directions; the others get no null handling
     * @throws UnknownSortPropertyException if the caller named anything else
     * @throws ResponseStatusException 400 when {@code page * size} passes
     *         {@code Integer.MAX_VALUE}: Spring Data computes the row offset as
     *         an {@code int}, and the query would fail as a 500
     */
    static Pageable stable(Pageable pageable, Set<String> sortable, Set<String> textual,
                           Set<String> nullable) {
        if ((long) pageable.getPageNumber() * pageable.getPageSize() > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "page * size must not exceed " + Integer.MAX_VALUE + ".");
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order order : pageable.getSort()) {
            if (!sortable.contains(order.getProperty())) {
                throw new UnknownSortPropertyException(order.getProperty());
            }
            boolean ignoreCase = order.isIgnoreCase() && textual.contains(order.getProperty());
            Sort.NullHandling nulls = nullable.contains(order.getProperty())
                    ? Sort.NullHandling.NULLS_LAST
                    : Sort.NullHandling.NATIVE;
            orders.add(new Sort.Order(order.getDirection(), order.getProperty(), ignoreCase, nulls));
        }
        Sort sort = Sort.by(orders);
        if (sort.getOrderFor(TIEBREAKER) == null) {
            sort = sort.and(Sort.by(Sort.Direction.ASC, TIEBREAKER));
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
