package com.smit.flightops.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.tests.EventLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link BookingEventHandler} against DynamoDB Local, on a table keyed as
 * {@code template.yaml} keys {@code FlightEventsTable}.
 *
 * <p>{@link BookingEventHandlerTest} mocks the client, so it checks the request the
 * handler builds but not what DynamoDB makes of it. An item that does not carry the
 * table's key, a condition expression DynamoDB cannot parse, or a condition that
 * never fires would all pass there. Here the requests reach a server that enforces
 * each of them. DynamoDB Local is an emulator: this is not AWS.
 *
 * <p>{@code disabledWithoutDocker = true}, like the service's PostgreSQL tests, so
 * read the skip count. CI runs it, and its "The emulator tests ran" step fails if
 * it skipped.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("BookingEventHandler against DynamoDB Local")
class BookingEventHandlerDynamoDbLocalTest {

    private static final String TABLE = "flight-status-events";

    /**
     * A release tag, not latest, so a new emulator release arrives only in a
     * commit that says so.
     * {@code -sharedDb} gives every key id and region one database, so the
     * table is there whatever the client signs with. {@code -disableTelemetry},
     * because by default the emulator reports usage to AWS.
     */
    @Container
    static GenericContainer<?> dynamoDbLocal =
            new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:3.3.1"))
                    .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb",
                            "-disableTelemetry")
                    .withExposedPorts(8000);

    private static DynamoDbClient dynamoDb;

    private BookingEventHandler handler;

    /** What the handler logged, to tell a refused duplicate from a second write. */
    private final List<String> logLines = new ArrayList<>();

    @BeforeAll
    static void connect() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(
                        "http://" + dynamoDbLocal.getHost() + ":" + dynamoDbLocal.getMappedPort(8000)))
                .region(Region.AP_SOUTH_1)
                // DynamoDB Local accepts only letters and digits in the key id.
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                // The HTTP client the handler uses in Lambda, and the only one on this classpath.
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    @AfterAll
    static void disconnect() {
        if (dynamoDb != null) {
            dynamoDb.close();
        }
    }

    /**
     * A fresh table per test, so no test can pass on another's items. The key schema
     * and attribute definitions are {@code FlightEventsTable}'s; change the two together.
     */
    @BeforeEach
    void createTable() {
        dynamoDb.createTable(request -> request
                .tableName(TABLE)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder()
                                .attributeName("flightNumber").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder()
                                .attributeName("eventTime").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder()
                                .attributeName("flightNumber").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder()
                                .attributeName("eventTime").keyType(KeyType.RANGE).build()));
        try (DynamoDbWaiter waiter = dynamoDb.waiter()) {
            waiter.waitUntilTableExists(request -> request.tableName(TABLE));
        }
        handler = new BookingEventHandler(dynamoDb, TABLE);
    }

    @AfterEach
    void dropTable() {
        dynamoDb.deleteTable(request -> request.tableName(TABLE));
        try (DynamoDbWaiter waiter = dynamoDb.waiter()) {
            waiter.waitUntilTableNotExists(request -> request.tableName(TABLE));
        }
    }

    private Context context() {
        LambdaLogger logger = new LambdaLogger() {
            @Override
            public void log(String message) {
                logLines.add(message);
            }

            @Override
            public void log(byte[] message) {
                logLines.add(new String(message, StandardCharsets.UTF_8));
            }
        };
        Context context = mock(Context.class);
        when(context.getLogger()).thenReturn(logger);
        return context;
    }

    private static List<Map<String, AttributeValue>> allItems() {
        return dynamoDb.scan(request -> request.tableName(TABLE).consistentRead(true)).items();
    }

    private static Map<String, AttributeValue> item(String flightNumber, String eventTime) {
        Map<String, AttributeValue> item = dynamoDb.getItem(request -> request
                .tableName(TABLE)
                .key(Map.of(
                        "flightNumber", AttributeValue.fromS(flightNumber),
                        "eventTime", AttributeValue.fromS(eventTime)))
                .consistentRead(true)).item();
        assertThat(item).as("the item at %s / %s", flightNumber, eventTime).isNotEmpty();
        return item;
    }

    private static List<String> failedIds(SQSBatchResponse response) {
        return response.getBatchItemFailures().stream()
                .map(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
                .toList();
    }

    @Test
    @DisplayName("events/sqs.json lands as one item per booking, at the key the table declares")
    void theFixtureLandsAsOneItemPerBooking() {
        SQSBatchResponse response = handler.handleRequest(EventLoader.loadSQSEvent("sqs.json"), context());

        assertThat(failedIds(response)).isEmpty();
        assertThat(allItems()).hasSize(2);

        Map<String, AttributeValue> first = item("UA2402", "2026-09-15T09:41:12.481923Z#1001");
        assertThat(first.get("bookingId").s()).isEqualTo("1001");
        assertThat(first.get("bookingTime").s()).isEqualTo("2026-09-15T09:41:12.481923Z");
        assertThat(first.get("eventType").s()).isEqualTo("BOOKING_CREATED");
        // Stored as a number, which DynamoDB would refuse if the string were not one.
        assertThat(first.get("seats").n()).isEqualTo("2");

        Map<String, AttributeValue> second = item("UA1187", "2026-09-15T09:41:13.902117Z#1002");
        assertThat(second.get("bookingId").s()).isEqualTo("1002");
        assertThat(second.get("seats").n()).isEqualTo("1");
    }

    @Test
    @DisplayName("a redelivery is refused by the condition, is not a failure, and leaves one item per booking")
    void aRedeliveredBatchIsANoOp() {
        assertThat(failedIds(handler.handleRequest(EventLoader.loadSQSEvent("sqs.json"), context())))
                .isEmpty();
        logLines.clear();

        SQSEvent redelivery = EventLoader.loadSQSEvent("sqs.json");
        SQSBatchResponse response = handler.handleRequest(redelivery, context());

        assertThat(failedIds(response)).isEmpty();
        // The item count alone would also pass if the second write overwrote the
        // first. These lines are logged only when DynamoDB refused the write.
        assertThat(logLines).containsExactly(
                "Duplicate ignored: " + redelivery.getRecords().get(0).getMessageId(),
                "Duplicate ignored: " + redelivery.getRecords().get(1).getMessageId());
        assertThat(allItems())
                .extracting(item -> item.get("bookingId").s())
                .containsExactlyInAnyOrder("1001", "1002");
    }

    @Test
    @DisplayName("a malformed body is reported by its message id alone, and the rest of the batch lands")
    void onlyTheMalformedMessageIsReported() {
        SQSEvent event = EventLoader.loadSQSEvent("sqs.json");
        SQSEvent.SQSMessage malformed = event.getRecords().get(1);
        malformed.setBody("{\"bookingId\":\"1002\",\"flightNumber\":");

        SQSBatchResponse response = handler.handleRequest(event, context());

        assertThat(failedIds(response)).containsExactly(malformed.getMessageId());
        assertThat(allItems())
                .extracting(item -> item.get("bookingId").s())
                .containsExactly("1001");
    }
}
