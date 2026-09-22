package com.smit.flightops.exception;

/**
 * A {@code ?sort=} naming something the endpoint does not offer. Thrown by
 * {@code SortPolicy} against the endpoint's published list, because Spring Data
 * only resolves the property on a derived query. A declared {@code @Query}, such
 * as the bookings list's {@code JOIN FETCH}, passes it to Hibernate unchecked,
 * and the caller would get a 500 for a typo.
 */
public class UnknownSortPropertyException extends RuntimeException {

    private final String propertyName;

    public UnknownSortPropertyException(String propertyName) {
        super("'%s' is not a sortable property.".formatted(propertyName));
        this.propertyName = propertyName;
    }

    public String getPropertyName() { return propertyName; }
}
