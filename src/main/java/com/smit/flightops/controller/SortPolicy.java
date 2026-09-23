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
     * @throws UnknownSortPropertyException if the caller named anything else
     * @throws ResponseStatusException 400 when {@code page * size} passes
     *         {@code Integer.MAX_VALUE}: Spring Data computes the row offset as
     *         an {@code int}, and the query would fail as a 500
     */
    static Pageable stable(Pageable pageable, Set<String> sortable, Set<String> textual) {
        if ((long) pageable.getPageNumber() * pageable.getPageSize() > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "page * size must not exceed " + Integer.MAX_VALUE + ".");
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order order : pageable.getSort()) {
            if (!sortable.contains(order.getProperty())) {
                throw new UnknownSortPropertyException(order.getProperty());
            }
            orders.add(order.isIgnoreCase() && !textual.contains(order.getProperty())
                    ? new Sort.Order(order.getDirection(), order.getProperty(), false, order.getNullHandling())
                    : order);
        }
        Sort sort = Sort.by(orders);
        if (sort.getOrderFor(TIEBREAKER) == null) {
            sort = sort.and(Sort.by(Sort.Direction.ASC, TIEBREAKER));
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
