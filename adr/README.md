# Architecture decision records

One file per decision that had a real alternative. Each record gives the
situation that forced the choice, what I chose, what it costs, and what I
rejected. The rejected options are there because someone who was not in the
room will propose them again a year later.

I wrote every record after the decision it records. The "Taken in" column names
the commit in which the decision landed. The first commit, `4a9a5b9`, is from
15 September 2026. 0001–0014 were written on 22 September:

* 0001–0008 and 0011–0014 were written together in `040c3be`. For 0001–0008
  that was between about eight hours and seven days after the code. For
  0011–0014 it was between twenty minutes and an hour and a quarter.

* 0009 and 0010 were written in `92922cd`, the same commit as the deployment
  scripts. The eksctl cluster file, the SAM template and `ap-south-1` were
  already in `4a9a5b9`.

* 0015 was written on 25 September, ten days after `4a9a5b9`, the commit in
  which the queue, its dead-letter queue and the Lambda's event source
  mapping first appear.

None of them was an RFC that a team approved before any code existed.

A record's decision does not change once it is accepted. A decision that
changes gets a new record that supersedes the old one. The old one stays,
because the reasoning that was true at the time explains the code someone is
reading today. I correct wrong facts in place. Where a correction changes the
reasoning, a dated note says what the record used to claim, as in 0002, 0003,
0004, 0006, 0007, 0009 and 0012. The retry backoff in 0013 and the annotation
scope in 0012 were changed in place, and each record's status line says so.

| # | Decision | Status | Taken in |
|---|---|---|---|
| [0001](0001-transactional-outbox.md) | A transactional outbox, not a send after commit | accepted | `e83d846` |
| [0002](0002-pessimistic-locking.md) | Pessimistic row locks for seat inventory, with a bounded wait | accepted | `4a9a5b9`, `eac8cc4` |
| [0003](0003-identity-ids.md) | Database-assigned IDENTITY ids | accepted | `4a9a5b9` |
| [0004](0004-request-fingerprint.md) | Idempotency is the key *and* a fingerprint of the request | accepted | `eac8cc4` |
| [0005](0005-one-rule-set-for-basic-and-jwt.md) | One authorisation rule set for Basic and JWT | accepted | `e83d846` |
| [0006](0006-stateless-sessions-no-csrf.md) | Stateless sessions, and CSRF protection off | accepted | `e83d846` |
| [0007](0007-spring-boot-4.md) | Spring Boot 4.1 and Java 21 | accepted | `7b45b5b` |
| [0008](0008-standalone-lambda-consumer.md) | A standalone, frameworkless Lambda consumer on arm64 | accepted | `4a9a5b9` |
| [0009](0009-eksctl-and-sam-over-terraform.md) | eksctl, SAM and kustomize rather than Terraform | accepted | `4a9a5b9`, `92922cd` |
| [0010](0010-region-ap-south-1.md) | Everything in `ap-south-1` | accepted | `4a9a5b9`, `92922cd` |
| [0011](0011-correlation-ids-and-metrics.md) | Correlation ids, domain counters, and no exporter | accepted | `d18f58b`, `a6efc1c` |
| [0012](0012-openapi-public-read.md) | The OpenAPI document is public; the API it describes is not | accepted | `82ea9b4`, `ccad5b4`, `2fb66de` |
| [0013](0013-outbox-ceiling-and-retention.md) | The outbox is bounded: an attempt ceiling and a retention window | accepted | `a6efc1c`, `50e8871` |
| [0014](0014-quality-gates.md) | The build fails on architecture, coverage and dependency drift | accepted | `631f5f0`, `f8d2d4b` |
| [0015](0015-event-transport.md) | Events go to an SQS standard queue, not a JMS broker | accepted | `4a9a5b9` |

## Writing another one

Copy the shape of an existing record; there is no template file. Give it a title
that states the decision in the indicative and a `Status:` line naming the
commit. Then write Context, Decision, Consequences and Alternatives considered.
Number it next in sequence and link it from the table above. Cite code as
`path` or `path#symbol`, so `scripts/refcheck.py` fails CI if the
citation stops being true.

If you cannot write a real "Alternatives considered" for a record, it is
probably a description of how the code works. That belongs in
[`ARCHITECTURE.md`](../ARCHITECTURE.md).
