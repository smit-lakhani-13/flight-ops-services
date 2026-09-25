# 10. Everything in `ap-south-1`

Status: accepted (recorded 2026-09-22, decision taken with the deployment
tooling; the region was already set in `4a9a5b9`, and the `lib.sh` pin landed
in `92922cd`)

## Context

Every AWS resource in this project has to be in some region: the cluster, the
registry, the queue, the table, the database, the load balancer. Nothing forced
a particular one, which is why I am recording it. An unstated default gets
changed by someone who assumes it was arbitrary.

## Decision

`ap-south-1` (Mumbai), for all of it. The value is set in six places:

* `.github/workflows/build-and-deploy.yml`
* `deploy/aws/cluster.yaml`
* `deploy/aws/lib.sh`
* `k8s/base/configmap.yaml`
* `k8s/overlays/aws/kustomization.yaml`, in the ECR image name
* `src/main/resources/application.yml`, as the default for `AWS_REGION`

`deploy/aws/lib.sh` exports it, so no AWS CLI call in any script depends on the
caller's profile default.

## Consequences

* **Latency.** The operator is in India. Mumbai is single-digit milliseconds
  away. `us-east-1` is roughly 200ms round trip, which makes `kubectl` feel
  broken and makes the demo script's timings meaningless.

* **Cost.** Mumbai is slightly more expensive than `us-east-1`: t3.medium is
  $0.0448/hour against $0.0416, and EKS is the same $0.10 everywhere. The whole
  demo costs 8 to 9% more than it would in Virginia, about 60 cents a day,
  most of it the NAT gateway and the database. Against the latency, that is
  not a close call.

* **Data residency.** Booking records are personal data. Keeping them in the
  country is the answer that needs no further explanation to anyone who asks.

* **One region.** The reason is failure modes more than cost. Cross-region
  traffic is billed, but the bigger problem is that a region mismatch does not
  look like one. An ECR pull from the wrong region fails as an authentication
  error. IRSA against an STS endpoint in another region fails as a credentials
  error. Both send people to read IAM policies that are correct.

* **The pin is in `lib.sh`.** The operator's profile is the wrong place for it.
  `aws configure get region` can differ between the shell that created a stack
  and the shell that deletes it, with the same account and the same scripts.
  The teardown then reports a clean sweep, because it is looking in an empty
  region. `up.sh` warns if the profile default differs, and proceeds with the
  pinned value.

* **Some things arrive here later.** Bedrock's newest models, some EC2 instance
  families and occasional EKS addon versions reach Mumbai later than
  `us-east-1`. This project uses none of them. Check before adding something
  that assumes region parity.

## Alternatives considered

* **`us-east-1`.** Cheapest, every service launches there first, and it is the
  region every tutorial assumes. I rejected it on latency, and because it is
  the region with the most interesting outage history.

* **`ap-south-2` (Hyderabad).** Closer for some of India and newer, with fewer
  services and thinner third-party support.

* **Multi-region.** Nothing here has an availability requirement that justifies
  a second region, and the demo cost would double.
