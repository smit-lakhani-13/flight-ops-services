package com.smit.flightops.service;

import com.smit.flightops.dto.CreateFlightRequest;
import com.smit.flightops.dto.FlightDto;
import com.smit.flightops.entity.Flight;
import com.smit.flightops.entity.FlightStatus;
import com.smit.flightops.exception.DuplicateFlightException;
import com.smit.flightops.exception.FlightNotFoundException;
import com.smit.flightops.repository.FlightRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service logic in isolation. The repository is mocked, so these tests fail only when
 * the branching or the mapping is wrong.
 */
@ExtendWith(MockitoExtension.class)
class FlightServiceTest {

    @Mock
    private FlightRepository flightRepository;

    @InjectMocks
    private FlightService flightService;

    @Captor
    private ArgumentCaptor<Flight> flightCaptor;

    private static final Instant DEPARTURE = Instant.now().plus(Duration.ofHours(8));

    private Flight flight() {
        return new Flight("UA123", "EWR", "LHR", 180, DEPARTURE);
    }

    @Test
    void findByNumberMapsToDto() {
        when(flightRepository.findByFlightNumber("UA123")).thenReturn(Optional.of(flight()));

        FlightDto dto = flightService.findByNumber("UA123");

        assertThat(dto.flightNumber()).isEqualTo("UA123");
        assertThat(dto.availableSeats()).isEqualTo(180);
        assertThat(dto.status()).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("lower-case input finds the same flight: codes are normalised")
    void findByNumberNormalisesCase() {
        when(flightRepository.findByFlightNumber("UA123")).thenReturn(Optional.of(flight()));

        assertThat(flightService.findByNumber(" ua123 ").flightNumber()).isEqualTo("UA123");
    }

    @Test
    void findByNumberThrowsWhenMissing() {
        when(flightRepository.findByFlightNumber("XX999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> flightService.findByNumber("XX999"))
                .isInstanceOf(FlightNotFoundException.class)
                .hasMessageContaining("XX999");
    }

    @Test
    void createRejectsADuplicateFlightNumber() {
        when(flightRepository.existsByFlightNumber("UA123")).thenReturn(true);

        assertThatThrownBy(() -> flightService.create(
                new CreateFlightRequest("ua123", "ewr", "lhr", 180, DEPARTURE)))
                .isInstanceOf(DuplicateFlightException.class);

        verify(flightRepository, never()).save(any());
    }

    @Test
    @DisplayName("create upper-cases the flight number and both airport codes")
    void createNormalisesCodes() {
        when(flightRepository.existsByFlightNumber("UA123")).thenReturn(false);
        when(flightRepository.save(any(Flight.class))).thenAnswer(i -> i.getArgument(0));

        FlightDto dto = flightService.create(
                new CreateFlightRequest(" ua123 ", "ewr", "lhr", 180, DEPARTURE));

        verify(flightRepository).save(flightCaptor.capture());
        Flight saved = flightCaptor.getValue();
        assertThat(saved.getFlightNumber()).isEqualTo("UA123");
        assertThat(saved.getOrigin()).isEqualTo("EWR");
        assertThat(saved.getDestination()).isEqualTo("LHR");
        assertThat(dto.availableSeats()).isEqualTo(180);
    }

    @Test
    @DisplayName("search picks the query that matches the parameters actually supplied")
    void searchChoosesTheRightQuery() {
        Pageable page = PageRequest.of(0, 20);
        when(flightRepository.findByOriginAndDestination("EWR", "LHR", page))
                .thenReturn(new PageImpl<>(List.of(flight())));
        when(flightRepository.findByOrigin("EWR", page)).thenReturn(new PageImpl<>(List.of(flight())));
        when(flightRepository.findByDestination("LHR", page)).thenReturn(new PageImpl<>(List.of(flight())));
        when(flightRepository.findAll(page)).thenReturn(new PageImpl<>(List.of()));

        assertThat(flightService.search("ewr", "lhr", page)).hasSize(1);
        assertThat(flightService.search("ewr", null, page)).hasSize(1);
        assertThat(flightService.search(null, " lhr ", page)).hasSize(1);
        assertThat(flightService.search("  ", "", page)).isEmpty();

        verify(flightRepository).findByOriginAndDestination("EWR", "LHR", page);
        verify(flightRepository).findByOrigin("EWR", page);
        verify(flightRepository).findByDestination("LHR", page);
        verify(flightRepository).findAll(page);
    }

    @Test
    @DisplayName("updateStatus mutates the managed entity, so no explicit save is needed")
    void updateStatusReliesOnDirtyChecking() {
        Flight flight = flight();
        when(flightRepository.findByFlightNumber("UA123")).thenReturn(Optional.of(flight));

        FlightDto dto = flightService.updateStatus("UA123", FlightStatus.BOARDING);

        assertThat(flight.getStatus()).isEqualTo(FlightStatus.BOARDING);
        assertThat(dto.status()).isEqualTo("BOARDING");
        verify(flightRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancel is a status change, never a delete")
    void cancelDoesNotDelete() {
        Flight flight = flight();
        when(flightRepository.findByFlightNumber("UA123")).thenReturn(Optional.of(flight));

        flightService.cancel("UA123");

        assertThat(flight.getStatus()).isEqualTo(FlightStatus.CANCELLED);
        verify(flightRepository, never()).delete(any());
    }
}
