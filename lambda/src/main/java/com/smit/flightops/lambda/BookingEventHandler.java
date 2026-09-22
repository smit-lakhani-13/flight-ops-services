package com.smit.flightops.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 *
 * <p><b>4. The producer's trace id is logged, not regenerated.</b> The service
 * captures the booking request's W3C trace context at the moment of the booking
 * and carries it on the outbox row; {@code SqsEventPublisher} sends it as the
 * {@code traceparent} message attribute. Logging it here is what joins the two
 * halves: an engineer holding a trace id from an API response can find the log
 * line for the same booking in this function's log group, across a queue and a
 * process boundary that otherwise share nothing.
 *
 * <p>No tracing SDK is added to do it. An OpenTelemetry or X-Ray SDK in this
 * function would mean a span exporter, an endpoint to export to, and cold-start
 * cost on every invocation, in exchange for something one string in a log line
 * already provides at this size. See {@code contracts/README.md}.
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
     * <p>Tolerating a <em>missing</em> field is a different matter, and the
     * opposite decision: {@code FAIL_ON_NULL_FOR_PRIMITIVES} is switched on.
     * Jackson's record deserialiser passes null for a creator parameter that was
     * not present, and Java then unboxes null to {@code 0} for an {@code int}.
     * So a message whose {@code seats} field was absent, or explicitly
     * {@code null}, deserialised cleanly to {@code seats = 0}, passed the
     * conditional write, and was stored in DynamoDB as a booking for nought
     * seats — a success, permanently, with no retry and nothing in the DLQ.
     * A projection that silently reads zero is worse than one that is missing a
     * row, because nothing ever goes looking for it. With the feature enabled
     * both shapes throw {@code MismatchedInputException}, the message is
     * reported as a batch item failure, and it lands in the DLQ where somebody
     * can look at it.
     *
     * <p>ObjectMapper is thread-safe once configured, which is what makes sharing
     * one static instance safe.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

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
     *
     * <p><b>The timeouts are the other load-bearing part, and they are arithmetic
     * rather than taste.</b> The SDK's default {@code apiCallTimeout} is no
     * timeout at all, and its default DynamoDB retry policy is {@code LEGACY} —
     * nine attempts with up to 20 seconds of backoff. With neither bounded, one
     * degraded {@code PutItem} can consume the function's entire 30-second
     * {@code Timeout}, and when Lambda kills the invocation nothing returns:
     * the {@link SQSBatchResponse} that names the messages which actually
     * failed never leaves the function, so the whole batch of ten is redelivered
     * — including the ones already written. That defeats the partial-batch-
     * failure design the handler is built around and turns a slow dependency
     * into a redelivery storm.
     *
     * <p>So the budget is divided by the batch size: {@code template.yaml} sets
     * {@code BatchSize: 10} and {@code Timeout: 30}, which leaves 3 seconds per
     * message. {@code apiCallTimeout} is 2.5s, so ten fully-timed-out messages
     * cost 25s and the handler still returns its failure list inside the budget.
     * {@code apiCallAttemptTimeout} is 1s, leaving room for two attempts and the
     * backoff between them.
     *
     * <p>The HTTP client's own timeouts are set here too, and not for symmetry:
     * the SDK's default connection timeout is 2 seconds, which is longer than
     * the 1-second attempt timeout above. Left at the default, a cold-start
     * attempt could be aborted in the middle of its TLS handshake and every
     * retry would meet the same wall — a self-inflicted failure on the first
     * message of every cold invocation. 500ms is generous for DynamoDB from a
     * function that is deliberately not in a VPC.
     *
     * <p>{@code STANDARD} retry mode rather than {@code LEGACY}, matching
     * {@code AwsConfig} in the service: three attempts, and a retry-token bucket
     * so a dependency failing for everyone stops being retried rather than
     * having the retries pile on.
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
            // Read before the try, so a message that fails to parse still logs
            // the trace of the request that produced it. That is precisely the
            // message somebody will be looking for.
            String trace = tracePrefix(message);
            try {
                BookingEvent booking = MAPPER.readValue(message.getBody(), BookingEvent.class);
                dynamoDb.putItem(putRequest(booking));
                logger.log(trace + "Processed booking " + booking.bookingId());

            } catch (ConditionalCheckFailedException e) {
                // The item is already there, so this is a redelivery of a message
                // that already succeeded. Deleting it is the correct outcome:
                // returning it as a failure would loop it back to the queue and it
                // would fail the same way three times, then land in the DLQ as a
                // fake incident.
                logger.log(trace + "Duplicate ignored: " + message.getMessageId());

            } catch (Exception e) {
                // Deliberately includes JsonProcessingException, which is a
                // permanent failure — retrying malformed JSON three times cannot
                // help. Reporting it anyway is the lesser evil: the alternative is
                // not reporting it, which deletes the message and silently loses
                // the event. Two wasted retries buy a message preserved in the DLQ
                // where it can be inspected and redriven. Splitting parse failures
                // onto their own "quarantine" queue is the production refinement.
                logger.log(trace + "FAILED " + message.getMessageId() + ": "
                           + e.getClass().getSimpleName() + ": " + e.getMessage());
                failures.add(SQSBatchResponse.BatchItemFailure.builder()
                        .withItemIdentifier(message.getMessageId())
                        .build());
            }
        }

        return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
    }

    /**
     * The W3C traceparent shape, in full:
     * {@code version-traceid-parentid-flags}.
     *
     * <p>Matched rather than trusted, and the reason is not tidiness. This value
     * arrives from outside and goes straight into a log line, so an unvalidated
     * one is log injection: a newline in the middle of it forges a second log
     * entry that CloudWatch Logs Insights will happily parse as real, which is a
     * bad property for the record an incident is reconstructed from. A bounded
     * pattern with no whitespace in it removes that entirely, and it also caps
     * the length -- an SQS message attribute may be 256 KB, and paying to ship
     * that to CloudWatch on every invocation because a producer put something
     * strange in a diagnostic field is a bill, not an error.
     */
    private static final Pattern W3C_TRACEPARENT =
            Pattern.compile("[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

    /**
     * The log prefix carrying the producer's trace, or an empty string.
     *
     * <p>Every step here is a null that really happens. {@code
     * getMessageAttributes()} is null for an event whose JSON omits the field —
     * a hand-written fixture, a redriven DLQ message, or any producer that sends
     * no attributes at all. The attribute itself is absent whenever the booking
     * was made outside a traced request, which the service treats as normal and
     * refuses to paper over with a fabricated id. And {@code getStringValue()}
     * is null when the attribute was sent with a binary data type. A missing
     * trace must never fail a booking projection — it is a diagnostic, and the
     * event is the payload.
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
        // The trace id is the second field; searching the log group for it is
        // the whole point, and it is a substring of this either way.
        return "[traceparent=" + value + "] ";
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
     * only at a fixed width, and {@code Instant.toString()} — the JDK default,
     * and what this producer sent before {@code BookingCreatedEvent} was given
     * a formatter of its own — is not fixed width: it prints the shortest form
     * that round-trips, so a whole second comes out as
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
     * <p>Re-formatting here rather than trusting the producer is deliberate, and
     * it stays deliberate now that the producer pads too:
     * {@code BookingCreatedEvent.WIRE_TIME} uses the same pattern, so today the
     * two agree and this line is a no-op. That is the point — this consumer owns
     * its own key format, so a second producer on the contract, or a DLQ redrive
     * of a message written before that change, cannot corrupt the ordering of a
     * partition. It stays stable across redeliveries because parsing and
     * formatting are deterministic, so the same message always yields the same
     * key.
     *
     * <p>{@code begins_with(eventTime, "2026-09-15")} still works, and
     * {@code Query(flightNumber = "UA123")} now genuinely does return events in
     * time order.
     *
     * <p><b>The year is range-checked, and that is not paranoia.</b> Six
     * fractional digits fix the width of everything to the right of the
     * seconds, but nothing fixes the width to the <em>left</em> of it:
     * {@code Instant.parse} accepts expanded years, and the {@code uuuu}
     * pattern then emits a leading sign. A timestamp of
     * {@code +12026-09-15T10:00:00Z} — reachable by redriving a hand-edited DLQ
     * message, or from any second producer on this contract — formats to
     * twenty-nine characters beginning with {@code +} (0x2B), which is below
     * every ASCII digit. That row sorts <em>ahead of every other item in the
     * partition</em>, and {@code begins_with(eventTime, "2026-")} never matches
     * it, so it is simultaneously first in every query and invisible to the day
     * filter. A negative year does the same at twenty-eight characters. The
     * fixed-width guarantee holds for years 1000-9999 and this is where that is
     * enforced rather than assumed.
     *
     * @throws DateTimeException if the producer sent a timestamp that is not an
     *         instant, or one outside the range the key format can represent at
     *         a fixed width. Left to propagate: the caller reports the message
     *         as a batch item failure and it ends up in the DLQ, which is the
     *         right home for an event that breaks the wire contract. Silently
     *         falling back to the raw string would put an unsortable key in the
     *         table and call it a success.
     */
    static String sortKey(BookingEvent booking) {
        Instant at = Instant.parse(booking.timestamp());
        int year = at.atZone(ZoneOffset.UTC).getYear();
        if (year < MIN_SORTABLE_YEAR || year > MAX_SORTABLE_YEAR) {
            throw new DateTimeException(
                    "timestamp year " + year + " is outside the fixed-width sort-key range "
                    + MIN_SORTABLE_YEAR + "-" + MAX_SORTABLE_YEAR + ": " + booking.timestamp());
        }
        return SORT_KEY_TIME.format(at) + "#" + booking.bookingId();
    }

    /**
     * The years for which {@code uuuu} emits exactly four digits and no sign.
     * Outside this range the sort key changes width and the ordering guarantee
     * the whole format exists for stops holding.
     */
    private static final int MIN_SORTABLE_YEAR = 1000;
    private static final int MAX_SORTABLE_YEAR = 9999;

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
    public record BookingEvent(String bookingId, String flightNumber, int seats, String timestamp) {

        /**
         * The three string fields are checked here because Jackson cannot check
         * them: {@code FAIL_ON_NULL_FOR_PRIMITIVES} covers {@code seats} and
         * nothing covers a missing {@code String}, which binds to null.
         *
         * <p>Null reaches {@code AttributeValue.fromS(null)}, which does not
         * throw — it returns an {@code AttributeValue} carrying no datatype at
         * all, and {@code Map.of} accepts it. DynamoDB rejects the request,
         * so nothing wrong is written; but the failure arrives as a
         * {@code ValidationException} about a serialised request, three
         * receives later, in the DLQ, naming no field. Failing here names the
         * field in the first log line. A renamed field on the producer side is
         * the realistic way this happens, and it is precisely what
         * {@code contracts/booking-created-v1.json} exists to catch earlier.
         */
        public BookingEvent {
            requirePresent(bookingId, "bookingId");
            requirePresent(flightNumber, "flightNumber");
            requirePresent(timestamp, "timestamp");
        }

        private static void requirePresent(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "booking event is missing a value for '" + field + "'");
            }
        }
    }
}
