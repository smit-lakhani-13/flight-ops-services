# Architecture decision records

One file per decision that had a real alternative. Each records the situation
that forced the choice, what was chosen, what it costs, and what was rejected —
because the rejected option is the part that gets re-proposed a year later by
somebody who was not in the room.

**Every record here was written after the decision it records**, and the "Taken
in" column names the commit in which the decision actually landed. For 0001–0008
the gap is months; for 0009–0014 it is the same commit, because the record and
the code were written together but the record still followed the design rather
than preceding it. None of them was an RFC that a team approved before any code
existed, and saying so costs nothing — backdating them, and implying a process
that did not happen, is the kind of small dishonesty that makes a reader
discount everything else in the repository.

Records are immutable once accepted. A decision that changes gets a new record
that supersedes the old one; the old one stays, because the reasoning that was
true at the time is what explains the code somebody is reading today.

| # | Decision | Status | Taken in |
|---|---|---|---|
| [0001](0001-transactional-outbox.md) | A transactional outbox, not a send after commit | accepted | `e83d846` |
| [0002](0002-pessimistic-locking.md) | Pessimistic row locks for seat inventory, with a bounded wait | accepted | `4a9a5b9`, `eac8cc4` |
| [0003](0003-identity-ids.md) | Database-assigned IDENTITY ids | accepted | `4a9a5b9` |
| [0004](0004-request-fingerprint.md) | Idempotency is the key *and* a fingerprint of the request | accepted | `eac8cc4` |
| [0005](0005-one-rule-set-for-basic-and-jwt.md) | One authorisation rule set for Basic and JWT | accepted | `e83d846` |
| [0006](0006-stateless-sessions-no-csrf.md) | Stateless sessions, and CSRF deliberately off | accepted | `e83d846` |
| [0007](0007-spring-boot-4.md) | Spring Boot 4.1 and Java 21 | accepted | `7b45b5b` |
| [0008](0008-standalone-lambda-consumer.md) | A standalone, frameworkless Lambda consumer on arm64 | accepted | `4a9a5b9` |
| [0009](0009-eksctl-and-sam-over-terraform.md) | eksctl, SAM and kustomize rather than Terraform | accepted | 2026-09-22 |
| [0010](0010-region-ap-south-1.md) | Everything in `ap-south-1` | accepted | 2026-09-22 |
| [0011](0011-correlation-ids-and-metrics.md) | Correlation ids, three domain counters, and no exporter | accepted | `d18f58b` |
| [0012](0012-openapi-public-read.md) | The OpenAPI document is public; the API it describes is not | accepted | `82ea9b4` |
| [0013](0013-outbox-ceiling-and-retention.md) | The outbox is bounded: an attempt ceiling and a retention window | accepted | `a6efc1c` |
| [0014](0014-quality-gates.md) | The build fails on architecture, coverage and dependency drift | accepted | `631f5f0`, `f8d2d4b` |

## Writing another one

Copy the shape, not a template file: a title that states the decision in the
indicative, a `Status:` line naming the commit, then **Context**, **Decision**,
**Consequences** and **Alternatives considered**. Number it next in sequence,
link it from the table above, and cite code as `path` or `path#symbol` so
`scripts/refcheck.py` fails the build if it stops being true.

If a record has no honest "Alternatives considered", it is probably not a
decision — it is a description, and it belongs in
[`ARCHITECTURE.md`](../ARCHITECTURE.md).
