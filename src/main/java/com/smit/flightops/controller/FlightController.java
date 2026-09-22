package com.smit.flightops.controller;

import com.smit.flightops.dto.CreateFlightRequest;
import com.smit.flightops.dto.ErrorResponse;
import com.smit.flightops.dto.FlightDto;
import com.smit.flightops.dto.StatusUpdate;
import com.smit.flightops.dto.ValidationErrorResponse;
import com.smit.flightops.service.FlightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Set;

/**
 * HTTP adapter for flights: bind, delegate, map the status. No business rules
 * and no try/catch; errors go to
 * {@link com.smit.flightops.exception.GlobalExceptionHandler}.
 *
 * <p>JSON only. Without {@code produces}, {@code Accept: application/yaml} is
 * served YAML with epoch-number timestamps; with it, the caller gets a 406.
 */
@RestController
@RequestMapping(path = "/api/v1/flights", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Flights",
     description = "Schedule and seat inventory. Status moves through a state machine; "
                   + "cancelling a flight is a soft delete that its bookings outlive.")
public class FlightController {

    /** {@code version} is left out: ordering by it shows how often a row was written. */
    private static final Set<String> SORTABLE = Set.of(
            "id", "flightNumber", "origin", "destination",
            "totalSeats", "availableSeats", "status", "departureTime");

    private final FlightService flightService;

    public FlightController(FlightService flightService) {
        this.flightService = flightService;
    }

    @Operation(summary = "Get a flight")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The flight."),
            @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "`FORBIDDEN` — `flights:read` is required.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "`FLIGHT_NOT_FOUND`",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{flightNumber}")
    public FlightDto get(@PathVariable String flightNumber) {
        return flightService.findByNumber(flightNumber);
    }

    /**
     * Paged, so the response is bounded however large the table grows.
     * {@link SortPolicy} checks {@code sort} against {@link #SORTABLE} and adds
     * {@code id} as a tiebreaker, since two flights can leave at the same minute.
     */
    @Operation(summary = "Search flights by origin and destination")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of flights, by departure time unless `sort` says otherwise."),
            @ApiResponse(responseCode = "400", description = """
                    `UNKNOWN_SORT_PROPERTY` — `sort` names a property this endpoint does not offer. \
                    `MALFORMED_REQUEST` — `page` times `size` is larger than 2147483647.""",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "`FORBIDDEN` — `flights:read` is required.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public Page<FlightDto> search(@RequestParam(required = false) String origin,
                                  @RequestParam(required = false) String destination,
                                  @PageableDefault(size = 20, sort = "departureTime") Pageable pageable) {
        return flightService.search(origin, destination, SortPolicy.stable(pageable, SORTABLE));
    }

    /**
     * {@code Location} is built from the returned DTO, not the request, because
     * the service trims and upper-cases the number and only that form resolves.
     * The number is expanded as a URI variable, so it is encoded, never spliced in.
     */
    @Operation(summary = "Create a flight")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created. The body is the new flight.",
                    headers = @Header(name = "Location",
                            description = "`/api/v1/flights/{flightNumber}`, with the number trimmed and upper-cased.",
                            schema = @Schema(type = "string"))),
            @ApiResponse(responseCode = "400", description = """
                    `VALIDATION_FAILED` — a field is blank, out of range or has the wrong characters; \
                    `fieldErrors` names each one. `MALFORMED_REQUEST` — the body is not valid JSON, or \
                    `totalSeats` is missing or not a whole number; this one has the \
                    `{code, message, timestamp}` shape.""",
                    content = @Content(schema = @Schema(oneOf = {ValidationErrorResponse.class, ErrorResponse.class}))),
            @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "`FORBIDDEN` — `flights:write` is required.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = """
                    `DUPLICATE_FLIGHT` — a flight with this number exists. `DUPLICATE_REQUEST` — \
                    another request created the same number at the same moment.""",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<FlightDto> create(@Valid @RequestBody CreateFlightRequest request) {
        FlightDto flight = flightService.create(request);
        URI location = UriComponentsBuilder.fromPath("/api/v1/flights/{flightNumber}")
                .build(flight.flightNumber());
        return ResponseEntity.created(location).body(flight);
    }

    /** PATCH, not PUT: this replaces one field, not the resource. */
    @Operation(
            summary = "Move a flight to another status",
            description = """
                    `SCHEDULED → BOARDING → DEPARTED → ARRIVED`. Before `DEPARTED` a flight \
                    can also move to `DELAYED` or `CANCELLED`, and it can depart without \
                    `BOARDING` being recorded. A `DELAYED` flight goes on to `BOARDING`, \
                    `DEPARTED` or `CANCELLED`. `ARRIVED` and `CANCELLED` are terminal. Any \
                    other transition is 409 `ILLEGAL_STATUS_TRANSITION`.

                    Moving to the status the flight already has is allowed, so a retried \
                    PATCH is safe.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The flight, at its new status."),
            @ApiResponse(responseCode = "400", description = """
                    `VALIDATION_FAILED` — `status` is missing. `MALFORMED_REQUEST` — the body \
                    names a status that does not exist; this one has the \
                    `{code, message, timestamp}` shape.""",
                    content = @Content(schema = @Schema(oneOf = {ValidationErrorResponse.class, ErrorResponse.class}))),
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
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = """
                    `LOCK_TIMEOUT` — a booking or a booking cancellation held the flight row \
                    past `lock_timeout`. Carries `Retry-After`.""",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{flightNumber}/status")
    public FlightDto updateStatus(@PathVariable String flightNumber,
                                  @Valid @RequestBody StatusUpdate update) {
        return flightService.updateStatus(flightNumber, update.status());
    }

    /** Soft cancel: the flight row survives for its bookings. */
    @Operation(summary = "Cancel a flight",
               description = "Sets the status to `CANCELLED`. Cancelling a cancelled flight is a 204 no-op.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Cancelled, or already cancelled."),
            @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "`FORBIDDEN` — `flights:write` is required.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "`FLIGHT_NOT_FOUND`",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = """
                    `ILLEGAL_STATUS_TRANSITION` — the flight has departed or arrived. \
                    `CONCURRENT_MODIFICATION` — another write landed first.""",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = """
                    `LOCK_TIMEOUT` — a booking or a booking cancellation held the flight row \
                    past `lock_timeout`. Carries `Retry-After`.""",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{flightNumber}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable String flightNumber) {
        flightService.cancel(flightNumber);
    }
}
