# 4. Idempotency is the key *and* a fingerprint of the request

Status: accepted (recorded 2026-09-22, decision taken in commit `eac8cc4`)

## Context

`POST /api/v1/bookings` takes a client-supplied `idempotencyKey`, and
`uk_bookings_idempotency_key` guarantees one booking per key. The constraint
does what it was asked to do, and it is not enough.

A client that reused one key for a *different* request (different passenger,
different flight, different seat count) got `201` and the first booking's
details back. No seats were debited for the booking it believed it had just
made. No error told it so, and the confirmation named someone else. The
constraint cannot catch this, because the key was never the whole of the
request.

## Decision

I store a SHA-256 of the normalised request alongside the key
(`request_fingerprint`, added in
`src/main/resources/db/migration/V3__booking_request_fingerprint.sql`) and
compare it on every replay:

* same key, same fingerprint → replay the booking the key created, `201` (the
  body matches the first response until the booking is cancelled, when
  `cancelledAt` is set)
* same key, different fingerprint → `409 IDEMPOTENCY_KEY_REUSED`

`src/main/java/com/smit/flightops/dto/BookingRequest.java#fingerprint` computes
the fingerprint, and
`src/main/java/com/smit/flightops/entity/Booking.java#matchesRequest` compares
it.

## Consequences

* Replay is now safe in the way clients assume it is. Stripe's idempotency
  layer behaves this way, and clients are written against that expectation
  whether or not the server shares it.

* The column is nullable. Rows written before V3 have no fingerprint, and a hash
  of a request nobody kept cannot be backfilled. A null fingerprint counts as
  "cannot prove a mismatch", which fails open for legacy rows, and would for a
  row whose fingerprint was cleared on erasure (see the last bullet).

* Normalisation is part of the contract. Two requests that differ only in field
  order or in whitespace must hash the same, or clients see spurious 409s. This
  is why I compute the fingerprint from named components and ignore the raw
  body.

* **The hash is not secret.** It is one-way but unsalted, and its inputs are
  easy to guess. Anyone who can read the row can confirm a guessed passenger
  name for that flight and seat count. That exposes nothing new, because the
  same row already holds `passenger_name` in clear. If `passenger_name` is ever
  erased (on a data-deletion request, say), `request_fingerprint` has to be
  cleared with it.

**Correction (2026-09-23).** The last bullet used to say the stored hash
discloses nothing about the passenger, because a hash is one-way. It can
confirm a guess, as the bullet now says.

## Alternatives considered

* **Store the whole request body.** It allows a field-by-field diff in the 409
  message, and it puts passenger names in a second place forever. I rejected it
  on privacy and size.

* **Compare the parsed fields directly.** Equivalent for today's three fields,
  and one more column for every field added later. The hash keeps the schema
  stable.

* **Replay anyway.** Ignoring the mismatch and replaying the first booking is
  the original defect, restated as a policy.
