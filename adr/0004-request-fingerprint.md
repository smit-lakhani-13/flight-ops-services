# 4. Idempotency is the key *and* a fingerprint of the request

Status: accepted (recorded 2026-09-22, decision taken in commit `eac8cc4`)

## Context

`POST /api/v1/bookings` takes a client-supplied `idempotencyKey`, and
`uk_bookings_idempotency_key` guarantees one booking per key. That guarantee is
exactly what was asked of it, and it is not enough.

A client that reuses one key for a *different* request — different passenger,
different flight, different seat count — was given `201` and the **first**
booking's details back. No seats were debited for the booking it believed it
had just made, no error told it so, and the confirmation named somebody else.
The constraint cannot catch this; the key was never the whole of the request.

## Decision

Store a SHA-256 of the normalised request alongside the key
(`request_fingerprint`, added in
`src/main/resources/db/migration/V3__booking_request_fingerprint.sql`) and
compare it on every replay:

* same key, same fingerprint → replay the original response, `201`
* same key, different fingerprint → `409 IDEMPOTENCY_KEY_REUSED`

The fingerprint is computed in
`src/main/java/com/smit/flightops/dto/BookingRequest.java#fingerprint` and
compared in `src/main/java/com/smit/flightops/entity/Booking.java#matchesRequest`.

## Consequences

* Replay is now safe in the way clients assume it is. This is the behaviour
  Stripe's idempotency layer has, and clients are written against that
  expectation whether or not the server shares it.
* The column is **nullable**, because rows written before V3 have no
  fingerprint and a hash of a request nobody kept cannot be backfilled. A null
  fingerprint is treated as "cannot prove a mismatch", which fails open for
  legacy rows only.
* Normalisation is part of the contract: two requests that differ only in
  field order or in whitespace must hash the same, or clients see spurious
  409s. That is why the fingerprint is computed from named components rather
  than from the raw body.
* A hash is one-way, so the stored value discloses nothing about the passenger
  even though it is derived from their name.

## Alternatives considered

* **Store the whole request body.** Allows a precise diff in the 409 message
  and puts passenger names in a second place forever. Rejected on privacy and
  size.
* **Compare the parsed fields directly.** Equivalent for today's four fields
  and one more column per field added later. The hash keeps the schema stable.
* **Ignore the mismatch and replay anyway.** The original defect, restated as
  a policy.
