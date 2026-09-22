package com.smit.flightops.exception;

/**
 * A {@code ?sort=} parameter naming something the endpoint will not sort by.
 *
 * <p>Spring Data raises {@code PropertyReferenceException} for the same mistake
 * — but only on a <em>derived</em> query, where it resolves the property against
 * the entity before building the SQL. A repository method that declares its own
 * {@code @Query} gets no such resolution: the sort is appended to the JPQL
 * verbatim, the parse fails inside Hibernate, and the caller gets a 500 for a
 * typo. {@code BookingRepository.findByFlightNumber} is exactly that shape,
 * because it needs a {@code JOIN FETCH} to avoid an N+1.
 *
 * <p>So the property is checked in the controller instead, against a list the
 * endpoint publishes, and the check is not conditional on which kind of query
 * happens to sit underneath today. Swapping a derived query for a declared one
 * is a refactor nobody would expect to change an HTTP status code.
 */
public class UnknownSortPropertyException extends RuntimeException {

    private final String propertyName;

    public UnknownSortPropertyException(String propertyName) {
        super("'%s' is not a sortable property.".formatted(propertyName));
        this.propertyName = propertyName;
    }

    public String getPropertyName() { return propertyName; }
}
