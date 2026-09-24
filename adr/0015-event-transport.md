# 15. Events go to an SQS standard queue, not a JMS broker

Status: accepted (recorded 2026-09-25, decision taken in commit `4a9a5b9`,
before the outbox of ADR 0001 arrived in `e83d846`)

## Context

Solace PubSub+ and TIBCO EMS are described here from their vendors'
documentation, and Lambda's event sources from AWS's. None of them has been
used, built against or run in this project.

I chose SQS in the first commit, `4a9a5b9`, when the service still sent the
event from inside the booking transaction, and I recorded the choice after the
fact. It is judged here against the code as it is now. The consumer is a
Lambda ([ADR 0008](0008-standalone-lambda-consumer.md)). Since `e83d846`, the
outbox ([ADR 0001](0001-transactional-outbox.md)) gives at-least-once delivery
whatever the transport. And SQS leaves no broker to run or pay for.

The sending side is already transport-neutral.
`src/main/java/com/smit/flightops/service/EventPublisher.java#publish` takes
an event type, a serialised payload and a map of headers, and the mode in
`src/main/java/com/smit/flightops/config/EventProperties.java#MODES` picks the
implementation at startup:
`src/main/java/com/smit/flightops/service/LoggingEventPublisher.java` or
`src/main/java/com/smit/flightops/service/SqsEventPublisher.java`. The
consumer is not neutral.
`lambda/src/main/java/com/smit/flightops/lambda/BookingEventHandler.java#handleRequest`
takes an `SQSEvent` and returns an `SQSBatchResponse`.

## Decision

An SQS standard queue, read by the Lambda through an event source mapping,
with a dead-letter queue after three receives.

## Consequences

* The dead-letter queue is the SQS queue's own `RedrivePolicy`
  (`template.yaml#RedrivePolicy`, three receives). Partial-batch response is a
  setting on Lambda's event source mapping
  (`template.yaml#ReportBatchItemFailures`), which the AWS documentation
  offers for SQS, Kinesis, DynamoDB Streams and Kafka but not for Amazon MQ.

* The consumer is bound to `SQSEvent`. Another transport means another
  consumer, not a configuration change.

* A standard queue rather than FIFO, because nothing depends on order.
  `BookingCreated` is the only event. Each one writes its own item, keyed by
  flight number and by a `timestamp#bookingId` taken from the event
  (`lambda/src/main/java/com/smit/flightops/lambda/BookingEventHandler.java#sortKey`),
  and no event changes another's item, so any arrival order leaves the same
  table. This holds only while there is one event type. The handler stores
  `eventType` as the constant `BOOKING_CREATED` and never reads the
  `eventType` attribute
  (`lambda/src/main/java/com/smit/flightops/lambda/BookingEventHandler.java#putRequest`),
  so a second event, such as a cancellation, would need the consumer to route
  on that attribute and could need per-booking order. FIFO would also need a
  `MessageGroupId` on every send in
  `src/main/java/com/smit/flightops/service/SqsEventPublisher.java#publish`,
  and its deduplication covers only five minutes, so the conditional write
  would still be needed.

* At-least-once delivery costs the consumer one conditional write.
  `attribute_not_exists(bookingId)` in
  `lambda/src/main/java/com/smit/flightops/lambda/BookingEventHandler.java#putRequest`
  turns a redelivered message into a no-op. It makes a duplicate harmless; it
  is not what makes order irrelevant.

## Alternatives considered

* **A JMS broker (Solace PubSub+ or TIBCO EMS), from the vendors'
  documentation, not run here.** `EventPublisher` itself would not change,
  because the payload is already serialised. The rest is more than one class:
  * On the sending side: a publisher behind `@ConditionalOnProperty` that
    sends the payload unchanged as the body of a `TextMessage`, since
    `src/main/java/com/smit/flightops/service/EventPublisher.java#publish`
    receives it as a `String` and SQS sends it as the message body.
    `eventType` and each non-blank header, today only `traceparent`, would be
    set as String properties with `Message#setStringProperty`, as
    `src/main/java/com/smit/flightops/service/SqsEventPublisher.java#attributes`
    sets them as String message attributes now. Delivery would be persistent,
    to a queue with a topic subscription.
  * A `ConnectionFactory` bean in a configuration class of its own, like
    `src/main/java/com/smit/flightops/config/AwsConfig.java#sqsClient`, would
    be the only vendor-specific code. The vendor's client library in
    `pom.xml`, which `requireUpperBoundDeps` and the `dependency-review` job
    would judge, its connection settings and their secret, and the broker-side
    queue and dead-message queue are vendor-specific too. So are a mode in
    `src/main/java/com/smit/flightops/config/EventProperties.java#MODES` and a
    test.
  * On the consuming side, the event source mapping goes, because Lambda has
    no event source for Solace PubSub+ or TIBCO EMS. Its broker event sources,
    by the AWS Lambda documentation, are Amazon MQ (ActiveMQ, read over JMS,
    and RabbitMQ), Amazon MSK and self-managed Kafka. Even on Amazon MQ for
    ActiveMQ, the handler would receive an `ActiveMQEvent`, not an `SQSEvent`,
    and a failed message retries the whole batch. The replacement is one of
    two things. One is a bridge from the broker into SQS, in front of the
    existing mapping. The other is a `@JmsListener` on a transacted session
    (`sessionTransacted = true`, which Spring's
    `AbstractMessageListenerContainer` documentation recommends for redelivery
    on an exception; `CLIENT_ACKNOWLEDGE` is only best-effort), with the
    conditional `PutItem` kept to absorb redeliveries. That listener is an
    always-on Spring consumer, the option
    [ADR 0008](0008-standalone-lambda-consumer.md) rejected for its idle cost.
  * Neither broker is coded in this repository. Solace's Jakarta JMS client is
    on Maven Central (`com.solacesystems:sol-jms-jakarta`). The TIBCO EMS
    client jars ship with an EMS installation and are not on Maven Central, so
    an EMS build would first need them in a private repository.

* **XA across PostgreSQL and the broker in place of the outbox.** It needs a
  JTA transaction manager to coordinate the two and recovery for in-doubt
  transactions, and [ADR 0001](0001-transactional-outbox.md) already rejects
  two-phase commit. The outbox keeps the database the only resource that has
  to commit. A local transacted session on the producer is no substitute: it
  commits only on the broker, so committing it next to the database commit is
  the send-after-commit that ADR 0001 rejects.

* **EventBridge.** A bus that routes each event by rule to many targets. There
  is one consumer and nothing to route. A Lambda target is invoked
  asynchronously, one event at a time, without the batches and the
  partial-batch response the handler is built on. A bus in front of this queue
  would add a hop and a rule and change nothing else.

* **Kafka (Amazon MSK or self-managed).** Brokers to run and pay for while
  idle, bought for per-partition ordering and replay that one idempotent
  consumer does not use. Lambda can read Kafka, but the handler would receive
  a `KafkaEvent`, not an `SQSEvent`, so the consumer would change too.
