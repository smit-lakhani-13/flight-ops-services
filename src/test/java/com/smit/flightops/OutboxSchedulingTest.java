package com.smit.flightops;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.dto.CreateFlightRequest;
import com.smit.flightops.service.BookingService;
import com.smit.flightops.service.EventPublisher;
import com.smit.flightops.service.FlightService;
import com.smit.flightops.service.OutboxPruner;
import com.smit.flightops.service.OutboxPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * The outbox jobs running on their own schedule. Every other outbox test parks
 * the schedule an hour out and calls the job directly, so none of them notices a
 * missing {@code @EnableScheduling} or {@code @Scheduled}. Both jobs would then
 * never run, with no error, and every booking's event would stay unsent.
 *
 * <p>A 100ms drain on its own database. {@code @DirtiesContext} closes the context
 * after the class, so the fast drainer does not stay in the context cache and race
 * another class's direct calls.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:outboxscheduling;DB_CLOSE_DELAY=-1",
                "app.outbox.poll-interval=100",
                // Unlike every default, so a job bound to the wrong property shows.
                "app.outbox.prune-interval=2h"
        })
@DirtiesContext
class OutboxSchedulingTest {

    @Autowired private BookingService bookingService;
    @Autowired private FlightService flightService;
    @Autowired private ObjectProvider<ScheduledTaskHolder> scheduledTaskHolders;

    @MockitoBean private EventPublisher eventPublisher;

    /**
     * Matched by name: {@link Task} wraps the method's runnable, and the wrapper's
     * {@code toString} is the method's, as the actuator lists it.
     */
    private List<Task> scheduledTasksRunning(Class<?> type, String method) {
        String name = type.getName() + "." + method;
        return scheduledTaskHolders.stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .map(ScheduledTask::getTask)
                .filter(task -> task.getRunnable().toString().equals(name))
                .toList();
    }

    @Test
    @DisplayName("the drain and the prune are fixed-delay jobs at their configured intervals")
    void bothJobsAreScheduledAtTheConfiguredDelays() {
        assertThat(scheduledTasksRunning(OutboxPublisher.class, "drainOutbox"))
                .as("the scheduler holds one job for OutboxPublisher.drainOutbox")
                .singleElement()
                .isInstanceOfSatisfying(FixedDelayTask.class, drain ->
                        assertThat(drain.getIntervalDuration())
                                .as("the drain's delay is app.outbox.poll-interval")
                                .isEqualTo(Duration.ofMillis(100)));

        // The initial delay too: the first prune waits an interval, clear of startup.
        assertThat(scheduledTasksRunning(OutboxPruner.class, "prunePublishedEvents"))
                .as("the scheduler holds one job for OutboxPruner.prunePublishedEvents")
                .singleElement()
                .isInstanceOfSatisfying(FixedDelayTask.class, prune -> {
                    assertThat(prune.getIntervalDuration())
                            .as("the prune's delay is app.outbox.prune-interval")
                            .isEqualTo(Duration.ofHours(2));
                    assertThat(prune.getInitialDelayDuration())
                            .as("the prune's initial delay is app.outbox.prune-interval")
                            .isEqualTo(Duration.ofHours(2));
                });
    }

    /** Nothing here calls {@code drainOutbox}, so only the scheduler can have sent it. */
    @Test
    @DisplayName("a committed booking's event is sent with nobody calling the drain")
    void aCommittedBookingIsSentWithoutAnyoneCallingTheDrain() {
        flightService.create(new CreateFlightRequest("OS001", "EWR", "LHR", 20,
                Instant.now().plus(Duration.ofHours(6))));

        BookingDto booking = bookingService.book(
                new BookingRequest("OS001", "Test Passenger", 1, "sched-1"));

        // Fifty poll intervals: a bound on waiting for ever, not a latency target.
        verify(eventPublisher, timeout(5_000)).publish(eq("BookingCreated"),
                contains("\"bookingId\":\"" + booking.bookingId() + "\""), anyMap());
    }
}
