package com.smit.flightops.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Consumes {@code BookingCreated} events from SQS and projects them into a
 * DynamoDB table of flight events.
 *
 * <p>The producer is the main service's transactional outbox, drained by
 * {@code OutboxPublisher} through {@code SqsEventPublisher}. The wire contract
 * is {@code contracts/booking-created-v1.json}, which both sides test against
 * without sharing a jar — see {@code BookingEventContractTest} — and
 * {@link BookingEvent} below must stay field-compatible with it.
 *
 * <h2>Design notes</h2>
 *
 * <p><b>1. Initialisation happens once per execution environment.</b> The
 * DynamoDB client and the ObjectMapper are built during the cold start and reused
 * by every warm invocation. Building the client inside {@code handleRequest}
 * would re-resolve the region and the credential chain for every single message —
 * the single most common and most expensive Lambda mistake.
 *
 * <p><b>2. Partial batch response.</b> With {@code BatchSize: 10} and no
 * {@code ReportBatchItemFailures}, one poison message fails the whole batch and
 * all ten are redelivered — so the nine that already succeeded get reprocessed.
 * Returning per-message failures means only the broken one goes back to the
 * queue.
 *
 * <p><b>3. Idempotency via a conditional write.</b> SQS standard queues are
 * at-least-once, so duplicate delivery is a certainty over time, not a risk. The
 * {@code attribute_not_exists} condition makes reprocessing a no-op instead of a
 * double-count.
 */
public class BookingEventHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    /**
     * Tolerating unknown fields is a deliberate compatibility decision, not
     * laziness. The producer and this consumer deploy independently, so the
     * producer will at some point ship a new field first. With
     * FAIL_ON_UNKNOWN_PROPERTIES left on (Jackson's default) that deploy breaks
     * every message in flight and fills the DLQ. Additive schema changes have to
     * be non-events for the consumer.
     *
     * <p>ObjectMapper is thread-safe once configured, which is what makes sharing
     * one static instance safe.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * Initialisation-on-demand holder.
     *
     * <p>{@code DynamoDbClient.create()} resolves a region and credentials while
     * it builds. As a plain {@code static final} field that runs at class-load
     * time, so merely loading this class in an environment with no AWS
     * configuration throws {@code SdkClientException: Unable to load region} —
     * and a unit test cannot even instantiate the handler, let alone mock the
     * client.
     *
     * <p>Nesting it in a holder class defers that to first use. The package-private
     * constructor never touches {@code Holder}, so the class is never loaded during
     * tests and no credential resolution is attempted. The JVM still guarantees
     * exactly-once, thread-safe initialisation, so the cold-start-once property
     * from point 1 above is fully preserved.
     *
     * <p>The HTTP client is named explicitly rather than left to
     * {@code DynamoDbClient.create()}. Left implicit, the SDK discovers it by
     * scanning {@code META-INF/services} — and shade merges every provider it finds
     * into one file, so which implementation you get is decided by jar ordering.
     * There really were two candidates here until the {@code <exclusions>} in
     * pom.xml removed them. Naming it means a future dependency that happens to
     * ship an HTTP client cannot silently change how this function talks to
     * DynamoDB.
     *
     * <p>{@code UrlConnectionHttpClient} is the Lambda-appropriate choice: it wraps
     * {@code java.net.HttpURLConnection}, so it adds 34 KB and no transitive
     * dependencies, where Apache adds ~723 classes to be loaded on every cold
     * start. The trade — no HTTP/2, no tunable connection pool, sync only — is
     * invisible to a function that makes one {@code PutItem} call. It would be the
     * wrong choice for the long-lived Spring Boot service.
     *
     * <p>Region and credentials are deliberately left to the default chain, which
     * Lambda populates from {@code AWS_REGION} and the execution role.
     */
    private static final class Holder {
        static final DynamoDbClient CLIENT = DynamoDbClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    /**
     * The constructor Lambda calls. Lambda instantiates the handler class once per
     * execution environment and reuses the instance, so these instance fields are
     * just as "cold start only" as static ones — the client is static anyway so
     * that stays true even if that ever changed.
     */
    public BookingEventHandler() {
        this(Holder.CLIENT, System.getenv("DDB_TABLE"));
    }

    /**
     * Test seam. Package-private rather than public: it exists for
     * {@code BookingEventHandlerTest}, not for callers.
     */
    BookingEventHandler(DynamoDbClient dynamoDb, String tableName) {
        if (tableName == null || tableName.isBlank()) {
            // Fail immediately and legibly. Left unchecked, a null table name
            // surfaces one message at a time as an SDK validation error with no
            // hint that an environment variable is missing.
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
            try {
                BookingEvent booking = MAPPER.readValue(message.getBody(), BookingEvent.class);
                dynamoDb.putItem(putRequest(booking));
                logger.log("Processed booking " + booking.bookingId());

            } catch (ConditionalCheckFailedException e) {
                // The item is already there, so this is a redelivery of a message
                // that already succeeded. Deleting it is the correct outcome:
                // returning it as a failure would loop it back to the queue and it
                // would fail the same way three times, then land in the DLQ as a
                // fake incident.
                logger.log("Duplicate ignored: " + message.getMessageId());

            } catch (Exception e) {
                // Deliberately includes JsonProcessingException, which is a
                // permanent failure — retrying malformed JSON three times cannot
                // help. Reporting it anyway is the lesser evil: the alternative is
                // not reporting it, which deletes the message and silently loses
                // the event. Two wasted retries buy a message preserved in the DLQ
                // where it can be inspected and redriven. Splitting parse failures
                // onto their own "quarantine" queue is the production refinement.
                logger.log("FAILED " + message.getMessageId() + ": "
                           + e.getClass().getSimpleName() + ": " + e.getMessage());
                failures.add(SQSBatchResponse.BatchItemFailure.builder()
                        .withItemIdentifier(message.getMessageId())
                        .build());
            }
        }

        return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
    }

    private PutItemRequest putRequest(BookingEvent booking) {
        return PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "flightNumber", AttributeValue.fromS(booking.flightNumber()),
                        "eventTime", AttributeValue.fromS(sortKey(booking)),
                        // Kept as its own attribute because eventTime is no longer
                        // a plain timestamp. Without this you cannot read a
                        // booking's time without string-splitting the sort key.
                        "bookingTime", AttributeValue.fromS(booking.timestamp()),
                        "bookingId", AttributeValue.fromS(booking.bookingId()),
                        // fromN, not fromS: DynamoDB's N type is what makes
                        // numeric comparisons and ADD updates work. Stored as a
                        // string on the wire, which is why the argument is a
                        // String — that is the DynamoDB wire format, not a bug.
                        "seats", AttributeValue.fromN(String.valueOf(booking.seats())),
                        "eventType", AttributeValue.fromS("BOOKING_CREATED")))
                // A conditional write is the whole idempotency mechanism. DynamoDB
                // evaluates the condition against the item at this exact primary
                // key, atomically, and rejects the write if it already exists.
                //
                // bookingId is written on every item, so "the bookingId attribute
                // does not exist" is equivalent to "no item exists at this key".
                // attribute_not_exists(flightNumber) — naming the partition key —
                // is the more canonical spelling of the same check.
                .conditionExpression("attribute_not_exists(bookingId)")
                .build();
    }

    /**
     * Fixed-width UTC instant: always exactly six fractional digits, always
     * {@code Z}. See {@link #sortKey} for why the width is the point.
     */
    private static final DateTimeFormatter SORT_KEY_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    /**
     * Composite sort key: {@code <fixed-width timestamp>#<bookingId>}.
     *
     * <p>THIS IS A CORRECTNESS FIX, NOT A STYLE CHOICE. Using the bare timestamp
     * as the sort key makes the primary key {@code (flightNumber, timestamp)}, and
     * two <em>different</em> bookings on the same flight created in the same
     * instant then collide on one key. The conditional write does exactly what it
     * was told, the second booking is rejected, and the handler logs
     * "Duplicate ignored" — silent data loss reported as success. Rare on a
     * quiet queue, reproducible the moment a load test fires concurrent bookings
     * at one flight.
     *
     * <p>Appending the bookingId makes the key unique per booking while staying
     * <em>stable</em> across redeliveries of the same message, because both halves
     * come from the persisted event rather than from
     * {@code Instant.now()}. Stability is the part that matters: a key derived
     * from anything generated at consume time would differ on every retry and the
     * conditional write would never detect a duplicate at all.
     *
     * <p><b>The timestamp is re-formatted rather than used as sent, and this
     * fixes a second bug that the previous version of this comment actively
     * denied.</b> It claimed sorting was unaffected because "the timestamp is
     * ISO-8601, which sorts lexicographically". ISO-8601 sorts lexicographically
     * only at a fixed width, and {@code Instant.toString()} — which is what the
     * producer sends — is not fixed width: it prints the shortest form that
     * round-trips, so a whole second comes out as
     * {@code 2026-09-22T10:00:00Z} with no fractional part at all, while the
     * next microsecond comes out as {@code 2026-09-22T10:00:00.000001Z}. Compare
     * those as strings and the nineteenth character is {@code Z} (0x5A) against
     * {@code .} (0x2E), so the <em>later</em> instant sorts <em>first</em>. A
     * {@code Query} on one flight therefore returned events out of order,
     * exactly and only around whole seconds, which is the kind of bug that
     * survives every test anyone thinks to write. Padding to six digits — the
     * precision PostgreSQL stores and {@code Booking.createdAt} truncates to —
     * makes every key the same length and the claim true.
     *
     * <p>Re-formatting here rather than trusting the producer is deliberate:
     * this consumer owns its own key format. It stays stable across
     * redeliveries because parsing and formatting are deterministic, so the
     * same message always yields the same key.
     *
     * <p>{@code begins_with(eventTime, "2026-09-15")} still works, and
     * {@code Query(flightNumber = "UA123")} now genuinely does return events in
     * time order.
     *
     * @throws DateTimeParseException if the producer sent a timestamp that is
     *         not an instant. Left to propagate: the caller reports the message
     *         as a batch item failure and it ends up in the DLQ, which is the
     *         right home for an event that breaks the wire contract. Silently
     *         falling back to the raw string would put an unsortable key in the
     *         table and call it a success.
     */
    static String sortKey(BookingEvent booking) {
        Instant at = Instant.parse(booking.timestamp());
        return SORT_KEY_TIME.format(at) + "#" + booking.bookingId();
    }

    /**
     * Mirror of {@code com.smit.flightops.dto.BookingCreatedEvent} in the main
     * service. Duplicated on purpose: a shared jar between producer and consumer
     * couples their deploys, which is precisely what an event-driven boundary is
     * supposed to avoid. The cost is that a rename on one side is not caught by
     * the compiler.
     *
     * <p>It is caught by {@code contracts/booking-created-v1.json}, which both
     * sides test against without either importing the other's code — see
     * {@code BookingEventContractTest} here and the file of the same name in
     * the service. That is the substitute for the type system across a queue,
     * and it fails in the producer's own build rather than in this one.
     */
    public record BookingEvent(String bookingId, String flightNumber, int seats, String timestamp) {}
}
