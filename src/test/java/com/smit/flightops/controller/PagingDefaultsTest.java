package com.smit.flightops.controller;

import com.smit.flightops.config.TimeConfig;
import com.smit.flightops.service.BookingService;
import com.smit.flightops.service.FlightService;
import com.smit.flightops.support.MetricsTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The page each list asks its service for when the caller leaves paging out,
 * and the {@code id} tiebreaker {@link SortPolicy} adds to every sort. Checked
 * on the captured {@code Pageable}, which is the ORDER BY the repository gets:
 * over tied rows H2 tends to return insertion order, so an end-to-end test
 * would pass without the tiebreaker. Orders are compared by property and
 * direction only, since where null rows sort is not what this class pins.
 * Set up as {@link FlightControllerTest} is, for the reasons it gives.
 */
@WebMvcTest({FlightController.class, BookingController.class})
@Import({TimeConfig.class, MetricsTestConfig.class})
@AutoConfigureMockMvc(addFilters = false)
class PagingDefaultsTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private FlightService flightService;
    @MockitoBean private BookingService bookingService;

    private static List<String> orders(Pageable pageable) {
        return pageable.getSort().stream()
                .map(order -> order.getProperty() + " " + order.getDirection())
                .toList();
    }

    @Test
    @DisplayName("the bookings list defaults to the first page of 20, oldest first, then by id")
    void bookingsDefaultToOldestFirstInPagesOfTwenty() throws Exception {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(bookingService.findByFlightNumber(any(), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/bookings").param("flightNumber", "UA123"))
                .andExpect(status().isOk());

        verify(bookingService).findByFlightNumber(any(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(orders(pageable.getValue())).containsExactly("createdAt ASC", "id ASC");
    }

    /** A second id order would do no harm, but doc/api.md says none is added. */
    @Test
    @DisplayName("a client sort on bookings gets id ascending after it, unless it already sorts by id")
    void aClientSortOnBookingsGetsTheIdTiebreaker() throws Exception {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(bookingService.findByFlightNumber(any(), any())).thenReturn(Page.empty());

        for (String sort : List.of("seats,desc", "passengerName,asc", "id,desc")) {
            mockMvc.perform(get("/api/v1/bookings").param("flightNumber", "UA123").param("sort", sort))
                    .andExpect(status().isOk());
        }

        verify(bookingService, times(3)).findByFlightNumber(any(), pageable.capture());
        List<Pageable> sent = pageable.getAllValues();
        assertThat(orders(sent.get(0))).containsExactly("seats DESC", "id ASC");
        assertThat(orders(sent.get(1))).containsExactly("passengerName ASC", "id ASC");
        assertThat(orders(sent.get(2))).containsExactly("id DESC");
    }

    @Test
    @DisplayName("the flight search defaults to the first page of 20 by departure time, with the same tiebreaker")
    void flightsGetTheIdTiebreakerToo() throws Exception {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(flightService.search(any(), any(), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/flights"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/flights").param("sort", "id,desc"))
                .andExpect(status().isOk());

        verify(flightService, times(2)).search(any(), any(), pageable.capture());
        List<Pageable> sent = pageable.getAllValues();
        assertThat(sent.get(0).getPageNumber()).isZero();
        assertThat(sent.get(0).getPageSize()).isEqualTo(20);
        assertThat(orders(sent.get(0))).containsExactly("departureTime ASC", "id ASC");
        assertThat(orders(sent.get(1))).containsExactly("id DESC");
    }
}
