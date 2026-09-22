package com.smit.flightops.controller;

import com.smit.flightops.dto.CreateFlightRequest;
import com.smit.flightops.dto.ErrorResponse;
import com.smit.flightops.dto.FlightDto;
import com.smit.flightops.dto.StatusUpdate;
import com.smit.flightops.service.FlightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * HTTP adapter, nothing more: bind, delegate, map the status code. No
 * business rules, no repository access, no try/catch — errors travel to
 * {@link com.smit.flightops.exception.GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/flights")
@Tag(name = "Flights",
     description = "Schedule and seat inventory. Status moves through a state machine; "
                   + "cancelling a flight is a soft delete that its bookings outlive.")
public class FlightController {

    private final FlightService flightService;

    public FlightController(FlightService flightService) {
        this.flightService = flightService;
    }

    @GetMapping("/{flightNumber}")
    public FlightDto get(@PathVariable String flightNumber) {
        return flightService.findByNumber(flightNumber);
    }

    /**
     * Paged, not a bare List: an unbounded collection endpoint is a load-bearing
     * outage waiting for the table to grow.
     */
    @GetMapping
    public Page<FlightDto> search(@RequestParam(required = false) String origin,
                                  @RequestParam(required = false) String destination,
                                  @PageableDefault(size = 20, sort = "departureTime") Pageable pageable) {
        return flightService.search(origin, destination, pageable);
    }

    /**
     * 201 with a {@code Location} header pointing at
     * {@code GET /api/v1/flights/{flightNumber}}. The number comes off the
     * returned DTO, not off the request, because the service normalises it
     * (trim + upper-case) — {@code Location} has to name the URL that actually
     * resolves, not the one the client typed.
     */
    @PostMapping
    public ResponseEntity<FlightDto> create(@Valid @RequestBody CreateFlightRequest request) {
        FlightDto flight = flightService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/flights/" + flight.flightNumber()))
                .body(flight);
    }

    /** PATCH, not PUT: this replaces one field, not the resource. */
    @Operation(
            summary = "Move a flight to another status",
            description = """
                    `SCHEDULED → BOARDING → DEPARTED → ARRIVED`, with `CANCELLED` \
                    reachable from anything not yet departed. `ARRIVED` and `CANCELLED` \
                    are terminal, and the transition table is exhaustive rather than a \
                    list of what is forbidden — the first version of this endpoint \
                    accepted `CANCELLED → SCHEDULED`, after which a cancelled flight \
                    sold seats again.

                    A transition to the status the flight already has is allowed, so a \
                    retried PATCH is safe.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The flight, at its new status."),
            @ApiResponse(responseCode = "400", description =
                    "`MALFORMED_REQUEST` — the body names a status that does not exist.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "`FORBIDDEN` — `flights:write` is required.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "`FLIGHT_NOT_FOUND`",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = """
                    `ILLEGAL_STATUS_TRANSITION` — the flight cannot reach that status \
                    from the one it is in. `CONCURRENT_MODIFICATION` — another write \
                    landed first and `@Version` rejected this one.""",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{flightNumber}/status")
    public FlightDto updateStatus(@PathVariable String flightNumber,
                                  @Valid @RequestBody StatusUpdate update) {
        return flightService.updateStatus(flightNumber, update.status());
    }

    /** Soft cancel — 204 with no body; the flight row survives for its bookings. */
    @DeleteMapping("/{flightNumber}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable String flightNumber) {
        flightService.cancel(flightNumber);
    }
}
