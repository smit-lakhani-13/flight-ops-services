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
        // The package-private constructor. Deliberately never touches the Holder
        // class, so DynamoDbClient.create() is not called and these tests need no
        // AWS region, credentials or network.
        handler = new BookingEventHandler(dynamoDb, TABLE);
    }

    /**
     * Built per-test rather than in {@code @BeforeEach} because
     * {@code MockitoExtension} uses strict stubs: a test that never reaches
     * {@code getLogger()} would fail with UnnecessaryStubbingException.
     */
    private static Context contextWithLogger() {
        Context context = mock(Context.class);
        when(context.getLogger()).thenReturn(mock(LambdaLogger.class));
        return context;
    }

    /**
     * The same context, but with a logger the test keeps hold of. The two trace
     * tests below are about the log line itself, which is the only place the
     * producer's trace id surfaces in this function.
     */
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

    // ------------------------------------------------------------------
    // Happy path, against the real payload shape Lambda delivers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("writes one item per message and reports no failures")
    void writesOneItemPerMessage() {
        // Loaded from events/sqs.json — the same file `sam local invoke -e` uses,
        // deserialised by Lambda's own serializer rather than a hand-built object,
        // so a change to the real SQS envelope shape would surface here.
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
        // N, not S — numeric type, string wire format.
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
                // Still ISO-8601-prefixed, which is what keeps range queries and
                // begins_with() working on the sort key.
                .startsWith("2026-09-15T09:41:12.481923Z");
    }

    // ------------------------------------------------------------------
    // The bug this handler deviates from the spec to fix
    // ------------------------------------------------------------------

    @Test
    @DisplayName("REGRESSION: two bookings on one flight in the same instant both get written")
    void sameInstantSameFlightDoesNotCollide() {
        // events/sqs-same-instant.json: bookings 2001 and 2002, both on UA2402,
        // both stamped 2026-09-15T09:41:12.481923Z.
        //
        // With the bare timestamp as the sort key these two share a primary key.
        // The second putItem's attribute_not_exists condition then fails, the
        // handler logs "Duplicate ignored", SQS deletes the message, and a real
        // booking is gone — reported as success. This test is the guard.
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
        // The property that makes the conditional write an idempotency check
        // rather than a coin flip: both halves of the key come from the persisted
        // event, so a redelivery two minutes later produces the identical key.
        // A key built from Instant.now() at consume time would differ every
        // attempt and duplicates would never be detected at all.
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

        // Reporting it as a failure would send it back to the queue, fail
        // identically three times, and land a message that ALREADY SUCCEEDED in
        // the DLQ as a fake incident.
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

        // Not all three. Without ReportBatchItemFailures + this per-message
        // handling, messages 1 and 3 would be redelivered and reprocessed too.
        assertThat(response.getBatchItemFailures())
                .extracting(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
                .containsExactly("msg-1");

        // The batch keeps going after a failure rather than aborting.
        verify(dynamoDb, times(3)).putItem(any(PutItemRequest.class));
    }

    @Test
    @DisplayName("malformed JSON is reported, so it reaches the DLQ instead of vanishing")
    void malformedJsonIsReported() {
        SQSBatchResponse response = handler.handleRequest(
                event("{not json at all"), contextWithLogger());

        // A message the handler does not report is DELETED by Lambda. Retrying
        // unparseable JSON cannot succeed, so two of the three attempts are
        // wasted — but the alternative is silently destroying the event.
        assertThat(response.getBatchItemFailures())
                .extracting(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
                .containsExactly("msg-0");
        verifyNoInteractions(dynamoDb);
    }

    // ------------------------------------------------------------------
    // Schema evolution and edge cases
    // ------------------------------------------------------------------

    @Test
    @DisplayName("unknown fields from a newer producer are ignored, not fatal")
    void toleratesUnknownFields() {
        // The producer and this consumer deploy independently, so the producer
        // WILL ship a new field first. With Jackson's default
        // FAIL_ON_UNKNOWN_PROPERTIES that deploy poisons every message in flight.
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
        // This used to be the worst failure shape available here. Jackson's
        // record deserialiser passes null for an absent creator parameter and
        // Java unboxes it to 0, so the item was written with seats = 0, the
        // conditional write succeeded, and nothing retried or alerted. A
        // projection that quietly reads zero is worse than one missing a row.
        String body = """
                      {"bookingId":"1001","flightNumber":"UA2402",
                       "timestamp":"2026-09-15T09:41:12.481923Z"}
                      """;

        SQSBatchResponse response = handler.handleRequest(event(body), contextWithLogger());

        assertThat(response.getBatchItemFailures())
                .extracting(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
                .containsExactly("msg-0");
        verifyNoInteractions(dynamoDb);
    }

    @Test
    @DisplayName("an explicitly null seats field fails the same way an absent one does")
    void anExplicitNullSeatsFieldIsNotWritten() {
        // The two shapes take different paths through Jackson and only one
        // feature covers both, so they are pinned separately.
        String body = """
                      {"bookingId":"1001","flightNumber":"UA2402","seats":null,
                       "timestamp":"2026-09-15T09:41:12.481923Z"}
                      """;

        SQSBatchResponse response = handler.handleRequest(event(body), contextWithLogger());

        assertThat(response.getBatchItemFailures())
                .extracting(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
                .containsExactly("msg-0");
        verifyNoInteractions(dynamoDb);
    }

    @Test
    @DisplayName("a renamed key field is reported with the field name, not as a DynamoDB error")
    void aMissingKeyFieldNamesItself() {
        // The realistic cause is the producer renaming flight_number. Nothing
        // in Jackson catches a missing String: it binds null,
        // AttributeValue.fromS(null) returns an AttributeValue with no
        // datatype rather than throwing, and the failure used to arrive from
        // DynamoDB three receives later naming nothing.
        String body = """
                      {"bookingId":"1001","seats":2,
                       "timestamp":"2026-09-15T09:41:12.481923Z"}
                      """;

        SQSBatchResponse response = handler.handleRequest(event(body), contextWithLogger());

        assertThat(response.getBatchItemFailures())
                .extracting(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
                .containsExactly("msg-0");
        verifyNoInteractions(dynamoDb);
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
        // getRecords() is null on a default-constructed SQSEvent, and a
        // NullPointerException inside the handler is an unhandled invocation
        // error — the whole batch retries three times and lands in the DLQ.
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
        // Lambda surfaces a constructor throw as an init error on the first
        // invocation, with the message in CloudWatch. Left unvalidated, a null
        // table name is an opaque SDK validation error repeated once per message.
        assertThatThrownBy(() -> new BookingEventHandler(dynamoDb, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DDB_TABLE");

        assertThatThrownBy(() -> new BookingEventHandler(dynamoDb, "   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DDB_TABLE");
    }

    // ------------------------------------------------------------------
    // The producer's trace, carried across the queue
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the producer's traceparent reaches the log line, and its absence is silent")
    void theProducersTraceReachesTheLog() {
        // events/sqs-with-trace.json holds both halves of the real distribution:
        // booking 1003 was made inside a traced HTTP request, so the service
        // stored the trace on the outbox row and SqsEventPublisher sent it as a
        // message attribute; booking 1004 was not, so it carries eventType only.
        // This log line is the whole join — it is what lets someone holding a
        // trace id from an API response find the projection of that booking in
        // a different process, on the far side of a queue.
        LambdaLogger logger = mock(LambdaLogger.class);

        SQSBatchResponse response = handler.handleRequest(
                EventLoader.loadSQSEvent("sqs-with-trace.json"), contextLoggingTo(logger));

        assertThat(response.getBatchItemFailures()).isEmpty();
        verify(logger, times(2)).log(logLine.capture());
        assertThat(logLine.getAllValues()).containsExactly(
                "[traceparent=00-5808f6bf5ea044458d4ea574d7adfcb7-ed4eca047d02925c-01] "
                + "Processed booking 1003",
                // No prefix, and no placeholder either. An invented trace id is
                // worse than none: it stitches unrelated work into one trace.
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

        // msg-0 keeps the null attribute map that event() builds — the shape a
        // producer sending no attributes at all delivers, and the shape of every
        // hand-written fixture. A NullPointerException here would be an unhandled
        // invocation error: the whole batch retries three times and hits the DLQ,
        // because of a diagnostic field.
        //
        // msg-1 sends the attribute with a binary data type, so getStringValue()
        // is null even though the attribute exists.
        SQSEvent.MessageAttribute binary = new SQSEvent.MessageAttribute();
        binary.setDataType("Binary");
        binary.setBinaryValue(ByteBuffer.wrap("00-abc".getBytes(StandardCharsets.UTF_8)));
        records.get(1).setMessageAttributes(Map.of("traceparent", binary));

        // msg-2 is the attack: a well-formed traceparent followed by a newline
        // and a second, entirely fabricated log line. Logged unvalidated, that
        // forged line is indistinguishable from a real one in CloudWatch Logs
        // Insights, and the record an incident gets reconstructed from now
        // contains a booking that never happened.
        records.get(2).setMessageAttributes(Map.of("traceparent", stringAttribute(
                "00-5808f6bf5ea044458d4ea574d7adfcb7-ed4eca047d02925c-01\n"
                + "Processed booking 9999")));

        SQSBatchResponse response = handler.handleRequest(event, contextLoggingTo(logger));

        // All three project. The trace is a diagnostic; the event is the payload.
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
}
