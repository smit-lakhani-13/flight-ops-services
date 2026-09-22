package com.smit.flightops.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Projects {@code BookingCreated} events from SQS into DynamoDB, one conditional
 * {@code PutItem} per message.
 *
 * <p>A bad message never makes the handler throw; it goes into the
 * {@link SQSBatchResponse} failure list instead. That only works because
 * {@code template.yaml} sets {@code FunctionResponseTypes: ReportBatchItemFailures}.
 * Without it Lambda ignores the list, treats the invocation as a success and
 * deletes every message in the batch, failures included.
 *
 * <p>See {@code contracts/README.md} for the wire contract and ADR 0008 for the design.
 */
public class BookingEventHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    /**
     * Shared with {@code BookingEventContractTest}, so the contract is parsed with
     * this configuration. Unknown fields are tolerated because the producer
     * deploys on its own and will ship a new field first. A missing, null, string
     * or fractional {@code seats} fails instead of being stored as 0 or 2.
     */
    static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    /**
     * Builds the client once per execution environment, on first use, so a unit
     * test that loads the handler resolves no region or credentials.
     *
     * <p>The HTTP client is named because discovery ranks Apache 5 above
     * URLConnection, and a dependency that brought Apache 5 back would take over.
     *
     * <p>The timeouts fit {@code template.yaml}'s 30-second {@code Timeout} over
     * {@code BatchSize: 10}. The 2.5-second call cap lets the handler return its
     * failure list before Lambda kills it and the whole batch comes back. The
     * connection timeout must stay under the 1-second attempt cap, or a slow
     * cold-start handshake fails every attempt.
     */
    private static final class Holder {
        static final DynamoDbClient CLIENT = DynamoDbClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofMillis(500))
                        .socketTimeout(Duration.ofSeconds(1)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofMillis(2500))
                        .apiCallAttemptTimeout(Duration.ofSeconds(1))
                        .retryStrategy(RetryMode.STANDARD)
                        .build())
                .build();
    }

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    /** The constructor Lambda calls, once per execution environment. */
    public BookingEventHandler() {
        this(Holder.CLIENT, System.getenv("DDB_TABLE"));
    }

    /** For tests: takes the client directly, so {@link Holder} is never loaded. */
    BookingEventHandler(DynamoDbClient dynamoDb, String tableName) {
        if (tableName == null || tableName.isBlank()) {
            // Failing at init names the variable; a null table fails each message as an SDK error.
            throw new IllegalStateException(
                    "DDB_TABLE environment variable is required (set by template.yaml)");
        }
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        LambdaLogger logger = context.getLogger();
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();

        List<SQSEvent.SQSMessage> records = event.getRecords();
        if (records == null || records.isEmpty()) {
            return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
        }

        for (SQSEvent.SQSMessage message : records) {
            // Read before the try, so a message that fails to parse still logs its trace.
            String trace = tracePrefix(message);
            try {
                BookingEvent booking = MAPPER.readValue(message.getBody(), BookingEvent.class);
                dynamoDb.putItem(putRequest(booking));
                logger.log(trace + "Processed booking " + printable(booking.bookingId()));

            } catch (ConditionalCheckFailedException e) {
                // A redelivery of a message that already succeeded. Reporting it
                // would loop it back until it reached the DLQ as a false incident.
                logger.log(trace + "Duplicate ignored: " + message.getMessageId());

            } catch (Exception e) {
                // Includes parse failures, which retrying cannot fix: reporting
                // them keeps the message for the DLQ, and not reporting deletes it.
                logger.log(trace + "FAILED " + message.getMessageId() + ": "
                           + e.getClass().getSimpleName() + ": " + printable(e.getMessage()));
                failures.add(SQSBatchResponse.BatchItemFailure.builder()
                        .withItemIdentifier(message.getMessageId())
                        .build());
            }
        }

        return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
    }

    /**
     * Checked before logging: a newline in the attribute would put a fabricated
     * line inside the log entry, and an unbounded one would ship to CloudWatch.
     */
    private static final Pattern W3C_TRACEPARENT =
            Pattern.compile("[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

    /**
     * The producer's {@code traceparent} as a log prefix, or "". Each null is a real
     * case (no attribute map, an untraced booking, a binary attribute), and a
     * missing trace never fails a projection.
     */
    private static String tracePrefix(SQSEvent.SQSMessage message) {
        Map<String, SQSEvent.MessageAttribute> attributes = message.getMessageAttributes();
        if (attributes == null) {
            return "";
        }
        SQSEvent.MessageAttribute attribute = attributes.get("traceparent");
        if (attribute == null) {
            return "";
        }
        String value = attribute.getStringValue();
        if (value == null || !W3C_TRACEPARENT.matcher(value).matches()) {
            return "";
        }
        return "[traceparent=" + value + "] ";
    }

    private static final Pattern NOT_PRINTABLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");

    /**
     * Jackson quotes rejected input in full. Its messages about this record run to
     * about 330 characters, with the field name last.
     */
    private static final int MAX_LOGGED_CHARS = 1000;

    /**
     * Replaces control, format and line-separator characters with {@code ?} and
     * caps the length. Exception messages need it too: Jackson and java.time quote
     * the input they rejected.
     */
    static String printable(String value) {
        if (value == null) {
            return "null";
        }
        String oneLine = NOT_PRINTABLE.matcher(value).replaceAll("?");
        return oneLine.length() <= MAX_LOGGED_CHARS
                ? oneLine
                : oneLine.substring(0, MAX_LOGGED_CHARS) + "...";
    }

    private PutItemRequest putRequest(BookingEvent booking) {
        return PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "flightNumber", AttributeValue.fromS(booking.flightNumber()),
                        "eventTime", AttributeValue.fromS(sortKey(booking)),
                        // Stored on its own because the sort key is not a plain timestamp.
                        "bookingTime", AttributeValue.fromS(booking.timestamp()),
                        "bookingId", AttributeValue.fromS(booking.bookingId()),
                        // N, so DynamoDB compares it as a number; the SDK takes N as a string.
                        "seats", AttributeValue.fromN(String.valueOf(booking.seats())),
                        "eventType", AttributeValue.fromS("BOOKING_CREATED")))
                // The idempotency check. bookingId is on every item, so its absence
                // means nothing exists at this key, and a redelivery becomes a no-op.
                .conditionExpression("attribute_not_exists(bookingId)")
                .build();
    }

    /** Always UTC. The tests run in Asia/Kolkata so that a system-zone formatter fails them. */
    private static final DateTimeFormatter SORT_KEY_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    /**
     * {@code <fixed-width timestamp>#<bookingId>}. The bookingId keeps two bookings
     * on one flight in the same instant from sharing a key, and both halves come
     * from the event, so a redelivery produces the same key.
     *
     * <p>DynamoDB compares sort keys as strings, which is time order only at a
     * fixed width ({@code Instant.toString()} sorts {@code ...:00Z} after
     * {@code ...:00.000001Z}). The consumer re-formats the timestamp itself,
     * so a second producer or a redriven old message cannot break the order.
     *
     * @throws DateTimeException if the timestamp is not an instant or its year is
     *         outside 1000-9999; the caller reports the message so it reaches the DLQ.
     */
    static String sortKey(BookingEvent booking) {
        Instant at = Instant.parse(booking.timestamp());
        int year = at.atZone(ZoneOffset.UTC).getYear();
        if (year < MIN_SORTABLE_YEAR || year > MAX_SORTABLE_YEAR) {
            throw new DateTimeException(
                    "timestamp year " + year + " is outside the sort-key range "
                    + MIN_SORTABLE_YEAR + "-" + MAX_SORTABLE_YEAR + ": " + booking.timestamp());
        }
        return SORT_KEY_TIME.format(at) + "#" + booking.bookingId();
    }

    /**
     * {@code uuuu} prints four unsigned digits for years 0-9999. Outside that a sign
     * changes the width and sorts the row ahead of its partition. 1000 is a sanity
     * bound, not a width limit: a booking dated earlier is corrupt.
     */
    private static final int MIN_SORTABLE_YEAR = 1000;
    private static final int MAX_SORTABLE_YEAR = 9999;

    /**
     * The consumer's copy of the service's {@code BookingCreatedEvent}. A shared jar
     * would tie the two deploys together; the contract tests catch a rename instead.
     */
    public record BookingEvent(String bookingId, String flightNumber, int seats, String timestamp) {

        /**
         * A missing string binds to null, and a renamed field would otherwise reach
         * DynamoDB as an error that names nothing. The seats rule is the service's {@code @Min(1)}.
         */
        public BookingEvent {
            requirePresent(bookingId, "bookingId");
            requirePresent(flightNumber, "flightNumber");
            requirePresent(timestamp, "timestamp");
            if (seats < 1) {
                throw new IllegalArgumentException(
                        "booking event has " + seats + " for 'seats'; expected at least 1");
            }
        }

        private static void requirePresent(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "booking event is missing a value for '" + field + "'");
            }
        }
    }
}
