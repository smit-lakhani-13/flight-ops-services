# 10. Everything in `ap-south-1`

Status: accepted (recorded 2026-09-22, decision taken with the deployment tooling)

## Context

Every AWS resource in this project has to be in some region: the cluster, the
registry, the queue, the table, the database, the load balancer. Nothing forced
a particular one, which is precisely why it is worth recording — an unstated
default is the kind of thing that gets changed by someone who assumes it was
arbitrary.

## Decision

`ap-south-1` (Mumbai), for all of it. The value is stated in four places, and
`deploy/aws/lib.sh` exports it so that no AWS CLI call in any script depends on
the caller's profile default.

## Consequences

* **Latency.** The operator is in India. Mumbai is single-digit milliseconds
  away; `us-east-1` is roughly 200ms round trip, which makes `kubectl` feel
  broken and makes the demo script's timings meaningless.
* **Cost is slightly higher than `us-east-1`** — t3.medium at $0.0448/hour
  against $0.0416, EKS the same $0.10 everywhere — so the whole demo costs
  about 4% more than it would in Virginia. Against the latency, that is not a
  close call.
* **Data residency.** Booking records are personal data. Keeping them in-country
  is the answer that needs no further explanation to anyone who asks.
* **One region, not several, and the reason is failure modes rather than cost.**
  Cross-region traffic is billed, but the real problem is that a region
  mismatch does not present as a region mismatch: an ECR pull from the wrong
  region fails as an authentication error, and IRSA against an STS endpoint in
  another region fails as a credentials error. Both send people to read IAM
  policies that are perfectly correct.
* **The pin is in `lib.sh`, not in the operator's profile.** `aws configure get
  region` can differ between the shell that created a stack and the shell that
  deletes it — same account, same scripts — and the teardown then reports a
  clean sweep because it is looking in an empty region. `up.sh` warns if the
  profile default differs, and proceeds with the pinned value.
* **Three services this project might have wanted are not in Mumbai first**, and
  none is used: Bedrock's newest models, some EC2 instance families, and
  occasional EKS addon versions arrive here later than in `us-east-1`. Worth
  knowing before adding something that assumes region parity.

## Alternatives considered

* **`us-east-1`.** Cheapest, every service launches there first, and it is the
  region every tutorial assumes. Rejected on latency, and on being the region
  with the most interesting outage history.
* **`ap-south-2` (Hyderabad).** Closer for some of India and newer, with fewer
  services and thinner third-party support.
* **Multi-region.** Nothing here has an availability requirement that justifies
  a second region, and the demo cost would double.
