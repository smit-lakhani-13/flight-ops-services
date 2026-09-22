package com.smit.flightops.controller;

import com.smit.flightops.exception.UnknownSortPropertyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * Two things every paged endpoint here needs from its {@code Pageable}, applied
 * in one place so the two list endpoints cannot drift apart.
 *
 * <p><strong>1. The sort property is checked against a list the endpoint
 * publishes.</strong> Not against the entity — against a list. The entity's
 * properties are an implementation detail, and sorting by all of them is a
 * decision nobody took: {@code idempotencyKey} is deliberately kept out of
 * {@code BookingDto} so a client cannot read other people's keys, and
 * {@code ?sort=idempotencyKey} would hand back the same information one bit at
 * a time. An allow-list says what is offered; a deny-list has to keep up.
 *
 * <p><strong>2. A unique tiebreaker is appended.</strong> Paging over a
 * non-unique {@code ORDER BY} is not stable: twenty bookings created in the
 * same second have no defined order between them, so page 0 and page 1 are two
 * independent queries that can return the same row twice and skip another
 * entirely. The database is not wrong to do it — {@code ORDER BY created_at}
 * only constrains rows with different timestamps. {@code id} is unique and
 * monotonic, so appending it makes the total order deterministic without
 * changing the order the caller asked for.
 *
 * <p>Both list endpoints documented this second property in their Javadoc and
 * neither implemented it, which is how it was found.
 */
final class SortPolicy {

    /** The primary key of both entities, and the only column guaranteed unique. */
    private static final String TIEBREAKER = "id";

    private SortPolicy() {
    }

    /**
     * @param pageable the bound request, defaults already applied
     * @param sortable the properties this endpoint offers, entity-property spelling
     * @throws UnknownSortPropertyException if the caller named anything else
     */
    static Pageable stable(Pageable pageable, Set<String> sortable) {
        Sort sort = pageable.getSort();
        for (Sort.Order order : sort) {
            if (!sortable.contains(order.getProperty())) {
                throw new UnknownSortPropertyException(order.getProperty());
            }
        }
        if (sort.getOrderFor(TIEBREAKER) == null) {
            sort = sort.and(Sort.by(Sort.Direction.ASC, TIEBREAKER));
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
