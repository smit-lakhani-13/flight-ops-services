package com.smit.flightops.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.tests.EventLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookingEventHandler")
class BookingEventHandlerTest {

    private static final String TABLE = "flight-status-events";

    @Mock
    private DynamoDbClient dynamoDb;

    @Captor
    private ArgumentCaptor<PutItemRequest> putItem;

    @Captor
    private ArgumentCaptor<String> logLine;

    private BookingEventHandler handler;

    @BeforeEach
    void setUp() {
        // The package-private constructor never loads Holder, so no AWS region,
        // credentials or network are needed.
        handler = new BookingEventHandler(dynamoDb, TABLE);
    }

    /**
     * Built per test, not in {@code @BeforeEach}: with strict stubs, a test that
     * never reaches {@code getLogger()} would fail with UnnecessaryStubbingException.
     */
    private static Context contextWithLogger() {
        Context context = mock(Context.class);
        when(context.getLogger()).thenReturn(mock(LambdaLogger.class));
        return context;
    }

    /** For the tests that assert on the log line itself. */
    private static Context contextLoggingTo(LambdaLogger logger) {
        Context context = mock(Context.class);
        when(context.getLogger()).thenReturn(logger);
        return context;
    }

    private static SQSEvent.MessageAttribute stringAttribute(String value) {
        SQSEvent.MessageAttribute attribute = new SQSEvent.MessageAttribute();
        attribute.setDataType("String");
        attribute.setStringValue(value);
        return attribute;
    }

    private static SQSEvent event(String... bodies) {
        List<SQSEvent.SQSMessage> records = new java.util.ArrayList<>();
        for (int i = 0; i < bodies.length; i++) {
            SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
            message.setMessageId("msg-" + i);
            message.setBody(bodies[i]);
            records.add(message);
        }
        SQSEvent event = new SQSEvent();
        event.setRecords(records);
        return event;
    }

    private static String body(String bookingId, String flightNumber, int seats, String timestamp) {
        return """
               {"bookingId":"%s","flightNumber":"%s","seats":%d,"timestamp":"%s"}
               """.formatted(bookingId, flightNumber, seats, timestamp);
    }

    /** A body whose seats value is raw JSON, for the shapes {@link #body} cannot express. */
    private static String bodyWithSeats(String bookingId, String seatsJson) {
        return body(bookingId, "UA1", 1, "2026-09-15T09:41:12.481923Z")
                .replace("\"seats\":1,", "\"seats\":" + seatsJson + ",");
    }

    private static List<String> failedIds(SQSBatchResponse response) {
        return response.getBatchItemFailures().stream()
                .map(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
                .toList();
    }

    // ------------------------------------------------------------------
    // Happy path, against the real payload shape Lambda delivers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("writes one item per message and reports no failures")
    void writesOneItemPerMessage() {
        // events/sqs.json is the file `sam local invoke -e` uses, loaded by
        // Lambda's own serialiser, so a change to the SQS envelope shows here.
        SQSEvent event = EventLoader.loadSQSEvent("sqs.json");

        SQSBatchResponse response = handler.handleRequest(event, contextWithLogger());

        verify(dynamoDb, times(2)).putItem(any(PutItemRequest.class));
        assertThat(response.getBatchItemFailures()).isEmpty();
    }

    @Test
    @DisplayName("maps every attribute onto the item, with the conditional write")
    void mapsAttributes() {
        handler.handleRequest(
                event(body("1001", "UA2402", 2, "2026-09-15T09:41:12.481923Z")),
                contextWithLogger());

        verify(dynamoDb).putItem(putItem.capture());
        PutItemRequest request = putItem.getValue();

        assertThat(request.tableName()).isEqualTo(TABLE);
        assertThat(request.conditionExpression()).isEqualTo("attribute_not_exists(bookingId)");
        assertThat(request.item()).containsOnlyKeys(
                "flightNumber", "eventTime", "bookingTime", "bookingId", "seats", "eventType");
        assertThat(request.item().get("flightNumber").s()).isEqualTo("UA2402");
        assertThat(request.item().get("bookingId").s()).isEqualTo("1001");
        assertThat(request.item().get("bookingTime").s()).isEqualTo("2026-09-15T09:41:12.481923Z");
        assertThat(request.item().get("eventType").s()).isEqualTo("BOOKING_CREATED");
        // N, not S: numeric type, string wire format.
        assertThat(request.item().get("seats").n()).isEqualTo("2");
        assertThat(request.item().get("seats").s()).isNull();
    }

    @Test
    @DisplayName("sort key is timestamp#bookingId, not the bare timestamp")
    void sortKeyIsComposite() {
        handler.handleRequest(
                event(body("1001", "UA2402", 2, "2026-09-15T09:41:12.481923Z")),
                contextWithLogger());

        verify(dynamoDb).putItem(putItem.capture());
        assertThat(putItem.getValue().item().get("eventTime").s())
                .isEqualTo("2026-09-15T09:41:12.481923Z#1001")
                // The ISO-8601 prefix keeps range queries and begins_with() working.
                .startsWith("2026-09-15T09:41:12.481923Z");
    }

    // ------------------------------------------------------------------
    // The composite sort key
    // ------------------------------------------------------------------

    @Test
    @DisplayName("REGRESSION: two bookings on one flight in the same instant both get written")
    void sameInstantSameFlightDoesNotCollide() {
        // events/sqs-same-instant.json holds bookings 2001 and 2002 on UA2402 with
        // one timestamp. Keyed on the bare timestamp, the second write would fail
        // its condition and be logged as a duplicate, losing a real booking.
        SQSEvent event = EventLoader.loadSQSEvent("sqs-same-instant.json");

        SQSBatchResponse response = handler.handleRequest(event, contextWithLogger());

        verify(dynamoDb, times(2)).putItem(putItem.capture());
        assertThat(response.getBatchItemFailures()).isEmpty();

        assertThat(putItem.getAllValues())
                .extracting(request -> request.item().get("eventTime").s())
                .containsExactly(
                        "2026-09-15T09:41:12.481923Z#2001",
                        "2026-09-15T09:41:12.481923Z#2002")
                .doesNotHaveDuplicates();

        // Same partition, so a Query on the flight still returns both.
        assertThat(putItem.getAllValues())
                .allSatisfy(request ->
                        assertThat(request.item().get("flightNumber").s()).isEqualTo("UA2402"));
    }

    @Test
    @DisplayName("the sort key is stable across redeliveries of the same message")
    void sortKeyIsStableAcrossRedeliveries() {
        // Both halves of the key come from the event, so a redelivery produces
        // the same key and the conditional write can recognise it.
        String sameBody = body("1001", "UA2402", 2, "2026-09-15T09:41:12.481923Z");

        handler.handleRequest(event(sameBody), contextWithLogger());
        handler.handleRequest(event(sameBody), contextWithLogger());

        verify(dynamoDb, times(2)).putItem(putItem.capture());
        assertThat(putItem.getAllValues())
                .extracting(request -> request.item().get("eventTime").s())
                .containsExactly(
                        "2026-09-15T09:41:12.481923Z#1001",
                        "2026-09-15T09:41:12.481923Z#1001");
    }

    // ------------------------------------------------------------------
    // Idempotency and partial batch failure
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a rejected conditional write is a duplicate, so it counts as success")
    void duplicateIsNotAFailure() {
        when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder()
                        .message("The conditional request failed").build());

        SQSBatchResponse response = handler.handleRequest(
                event(body("1001", "UA2402", 2, "2026-09-15T09:41:12.481923Z")),
                contextWithLogger());

        // Reported, a message that already succeeded would end up in the DLQ.
        assertThat(response.getBatchItemFailures()).isEmpty();
    }

    @Test
    @DisplayName("only the failing message is reported; the rest are deleted")
    void reportsOnlyTheFailingMessage() {
        when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(PutItemResponse.builder().build())
                .thenThrow(DynamoDbException.builder().message("throughput exceeded").build())
                .thenReturn(PutItemResponse.builder().build());

        SQSBatchResponse response = handler.handleRequest(
                event(body("1", "UA1", 1, "2026-09-15T09:00:00Z"),
                      body("2", "UA2", 1, "2026-09-15T09:00:01Z"),
                      body("3", "UA3", 1, "2026-09-15T09:00:02Z")),
                contextWithLogger());

        // A handler that threw instead would have all three redelivered.
        assertThat(failedIds(response)).containsExactly("msg-1");

        // The batch keeps going after a failure.
        verify(dynamoDb, times(3)).putItem(any(PutItemRequest.class));
    }

    @Test
    @DisplayName("malformed JSON is reported, so it reaches the DLQ instead of vanishing")
    void malformedJsonIsReported() {
        SQSBatchResponse response = handler.handleRequest(
                event("{not json at all"), contextWithLogger());

        // Lambda deletes any message the handler does not report.
        assertThat(failedIds(response)).containsExactly("msg-0");
        verifyNoInteractions(dynamoDb);
    }

    // ------------------------------------------------------------------
    // Schema evolution and invalid payloads
    // ------------------------------------------------------------------

    @Test
    @DisplayName("unknown fields from a newer producer are ignored, not fatal")
    void toleratesUnknownFields() {
        // The producer deploys on its own and will ship a new field first.
        String body = """
                      {"bookingId":"1001","flightNumber":"UA2402","seats":2,
                       "timestamp":"2026-09-15T09:41:12.481923Z",
                       "cabinClass":"ECONOMY","loyaltyTier":"GOLD"}
                      """;

        SQSBatchResponse response = handler.handleRequest(event(body), contextWithLogger());

        verify(dynamoDb).putItem(putItem.capture());
        assertThat(putItem.getValue().item().get("bookingId").s()).isEqualTo("1001");
        assertThat(response.getBatchItemFailures()).isEmpty();
    }

    @Test
    @DisplayName("REGRESSION: a missing seats field is a failure, not a booking for nought seats")
    void anAbsentSeatsFieldIsNotWritten() {
        // Jackson passes null for an absent creator parameter and Java unboxes it
        // to 0, which would be stored as a successful booking for no seats.
        String body = """
                      {"bookingId":"1001","flightNumber":"UA2402",
                       "timestamp":"2026-09-15T09:41:12.481923Z"}
                      """;

        SQSBatchResponse response = handler.handleRequest(event(body), contextWithLogger());

        assertThat(failedIds(response)).containsExactly("msg-0");
        verifyNoInteractions(dynamoDb);
    }

    @Test
    @DisplayName("an explicitly null seats field fails the same way an absent one does")
    void anExplicitNullSeatsFieldIsNotWritten() {
        // An explicit null takes a different path through Jackson from an absent field.
        String body = """
                      {"bookingId":"1001","flightNumber":"UA2402","seats":null,
                       "timestamp":"2026-09-15T09:41:12.481923Z"}
                      """;

        SQSBatchResponse response = handler.handleRequest(event(body), contextWithLogger());

        assertThat(failedIds(response)).containsExactly("msg-0");
        verifyNoInteractions(dynamoDb);
    }

    @Test
    @DisplayName("a seats value that is not a positive whole number fails instead of being coerced")
    void aSeatsValueThatIsNotAPositiveWholeNumberIsNotWritten() {
        // Jackson's defaults would store "2" and 2.9 as two seats, and nothing
        // in Jackson rejects 0 or a negative count.
        SQSBatchResponse response = handler.handleRequest(
                event(bodyWithSeats("1", "0"), bodyWithSeats("2", "-3"),
                      bodyWithSeats("3", "\"2\""), bodyWithSeats("4", "2.9")),
                contextWithLogger());

        assertThat(failedIds(response)).containsExactly("msg-0", "msg-1", "msg-2", "msg-3");
        verifyNoInteractions(dynamoDb);
    }

    @Test
    @DisplayName("a renamed key field is reported with the field name, not as a DynamoDB error")
    void aMissingKeyFieldNamesItself() {
        // A missing string binds to null in Jackson. The log line has to name the
        // field, because that is what someone reading the DLQ starts from.
        LambdaLogger logger = mock(LambdaLogger.class);
        String noFlightNumber = """
                                {"bookingId":"1001","seats":2,
                                 "timestamp":"2026-09-15T09:41:12.481923Z"}
                                """;
        String noBookingId = """
                             {"flightNumber":"UA2402","seats":2,
                              "timestamp":"2026-09-15T09:41:12.481923Z"}
                             """;

        SQSBatchResponse response = handler.handleRequest(
                event(noFlightNumber, noBookingId), contextLoggingTo(logger));

        assertThat(failedIds(response)).containsExactly("msg-0", "msg-1");
        verifyNoInteractions(dynamoDb);
        verify(logger, times(2)).log(logLine.capture());
        assertThat(logLine.getAllValues().get(0))
                .contains("FAILED msg-0").contains("'flightNumber'");
        assertThat(logLine.getAllValues().get(1))
                .contains("FAILED msg-1").contains("'bookingId'");
    }

    @Test
    @DisplayName("an empty batch is a no-op, not a crash")
    void emptyBatchIsANoOp() {
        SQSEvent empty = new SQSEvent();
        empty.setRecords(List.of());

        SQSBatchResponse response = handler.handleRequest(empty, contextWithLogger());

        assertThat(response.getBatchItemFailures()).isEmpty();
        verifyNoInteractions(dynamoDb);
    }

    @Test
    @DisplayName("a null record list is a no-op, not a NullPointerException")
    void nullRecordListIsANoOp() {
        // A NullPointerException here would be an invocation error, and the
        // whole batch would be retried.
        SQSBatchResponse response = handler.handleRequest(new SQSEvent(), contextWithLogger());

        assertThat(response.getBatchItemFailures()).isEmpty();
        verifyNoInteractions(dynamoDb);
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a missing DDB_TABLE fails at initialisation, not per message")
    void missingTableNameFailsFast() {
        assertThatThrownBy(() -> new BookingEventHandler(dynamoDb, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DDB_TABLE");

        assertThatThrownBy(() -> new BookingEventHandler(dynamoDb, "   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DDB_TABLE");
    }

    // ------------------------------------------------------------------
    // What reaches the log
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the producer's traceparent reaches the log line, and its absence is silent")
    void theProducersTraceReachesTheLog() {
        // events/sqs-with-trace.json: booking 1003 was made in a traced request
        // and carries a traceparent attribute; booking 1004 carries none.
        LambdaLogger logger = mock(LambdaLogger.class);

        SQSBatchResponse response = handler.handleRequest(
                EventLoader.loadSQSEvent("sqs-with-trace.json"), contextLoggingTo(logger));

        assertThat(response.getBatchItemFailures()).isEmpty();
        verify(logger, times(2)).log(logLine.capture());
        assertThat(logLine.getAllValues()).containsExactly(
                "[traceparent=00-5808f6bf5ea044458d4ea574d7adfcb7-ed4eca047d02925c-01] "
                + "Processed booking 1003",
                // No placeholder: an invented trace id would join unrelated work.
                "Processed booking 1004");
    }

    @Test
    @DisplayName("a missing, binary or forged traceparent is ignored and the booking still lands")
    void aHostileTraceparentIsIgnored() {
        LambdaLogger logger = mock(LambdaLogger.class);
        SQSEvent event = event(
                body("1", "UA1", 1, "2026-09-15T09:00:00Z"),
                body("2", "UA2", 1, "2026-09-15T09:00:01Z"),
                body("3", "UA3", 1, "2026-09-15T09:00:02Z"));
        List<SQSEvent.SQSMessage> records = event.getRecords();

        // msg-0 has no attribute map at all, like a producer that sends none.
        // msg-1 sends the attribute as binary, so getStringValue() is null.
        SQSEvent.MessageAttribute binary = new SQSEvent.MessageAttribute();
        binary.setDataType("Binary");
        binary.setBinaryValue(ByteBuffer.wrap("00-abc".getBytes(StandardCharsets.UTF_8)));
        records.get(1).setMessageAttributes(Map.of("traceparent", binary));

        // msg-2 is a valid traceparent followed by a newline and a fabricated line.
        records.get(2).setMessageAttributes(Map.of("traceparent", stringAttribute(
                "00-5808f6bf5ea044458d4ea574d7adfcb7-ed4eca047d02925c-01\n"
                + "Processed booking 9999")));

        SQSBatchResponse response = handler.handleRequest(event, contextLoggingTo(logger));

        // All three project: the trace is a diagnostic, the event is the payload.
        assertThat(response.getBatchItemFailures()).isEmpty();
        verify(dynamoDb, times(3)).putItem(any(PutItemRequest.class));

        verify(logger, times(3)).log(logLine.capture());
        assertThat(logLine.getAllValues())
                .containsExactly(
                        "Processed booking 1", "Processed booking 2", "Processed booking 3")
                .allSatisfy(line -> assertThat(line)
                        .doesNotContain("traceparent")
                        .doesNotContain("\n"));
    }

    @Test
    @DisplayName("a value from the body reaches the log on one line and at a bounded length")
    void bodyValuesAreLoggedOnOneBoundedLine() {
        // The body comes from the same senders as the traceparent. The bookingId is
        // logged on success, and Jackson and java.time quote rejected input in
        // their messages, so both must reach the log printable and capped.
        LambdaLogger logger = mock(LambdaLogger.class);
        SQSEvent event = event(
                body("1\\r\\nProcessed booking 9999", "UA1", 1, "2026-09-15T09:41:12.481923Z"),
                body("2", "UA1", 1, "yesterday\\nProcessed booking 8888"),
                bodyWithSeats("3", "\"x\\nFORGED\""),
                bodyWithSeats("4", "\"" + "x".repeat(5000) + "\""));

        SQSBatchResponse response = handler.handleRequest(event, contextLoggingTo(logger));

        assertThat(failedIds(response)).containsExactly("msg-1", "msg-2", "msg-3");
        verify(logger, times(4)).log(logLine.capture());
        List<String> lines = logLine.getAllValues();
        assertThat(lines.get(0)).isEqualTo("Processed booking 1??Processed booking 9999");
        assertThat(lines.get(1)).startsWith("FAILED msg-1: DateTimeParseException: ");
        assertThat(lines.get(2)).startsWith("FAILED msg-2: MismatchedInputException: ");
        assertThat(lines.get(3)).startsWith("FAILED msg-3: ").endsWith("...").hasSizeLessThan(1100);
        assertThat(lines).allSatisfy(line -> assertThat(line).doesNotContain("\n", "\r"));
    }
}
