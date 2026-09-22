# Deployment

Three ways to run this, costing $0, about $0, and $7.72 a day. This document
covers all three, prices the third one honestly, and gives the exact commands
for creating and — more importantly — destroying it.

**Executed on: —**

That line is blank because nothing here has ever been run against a real AWS
account. The templates lint, the scripts are shellcheck-clean and parse, the
manifests validate against Kubernetes 1.36 — and none of that is the same as
having worked. When it is run, this line gets the dates and §8 gets the
evidence.

## Contents

- [1. Three shapes](#1-three-shapes)
- [2. Localhost](#2-localhost)
- [3. The async half alone](#3-the-async-half-alone)
- [4. The full thing on AWS](#4-the-full-thing-on-aws)
- [5. What it costs](#5-what-it-costs)
- [6. Tearing it down](#6-tearing-it-down)
- [7. What breaks first](#7-what-breaks-first)
- [8. HTTP, and what HTTPS would take](#8-http-and-what-https-would-take)

## 1. Three shapes

| | Proves | Needs | Cost |
|---|---|---|---|
| **Localhost** | the API, the outbox, idempotency, seat locking, every test | JDK 21, optionally Docker | $0 |
| **Async half on AWS** | outbox → SQS → Lambda → DynamoDB, on real infrastructure | an AWS account, SAM CLI | ~$0 (free tier) |
| **Full stack on EKS** | all of that plus rolling deploys, IRSA, HPA, a public URL | an AWS account, five CLIs, 50 minutes | **$7.72/day** |

The middle one is underrated. It is the interesting half of the architecture —
the transactional outbox crossing a real queue into a real consumer — and it
costs nothing, because SQS gives a million requests a month free forever,
DynamoDB on-demand charges per write rather than per hour, and an idle Lambda
costs nothing at all. The EKS half is where all the money is, and what it adds
is Kubernetes rather than anything about this system's design.

Two variants of the third shape, priced in [§5](#5-what-it-costs): public
subnets with no NAT gateway ($6.42/day), and a single EC2 instance running
`compose.yaml` with the SAM stack beside it ($0.80/day).

## 2. Localhost

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21      # or wherever your JDK 21 is
./mvnw spring-boot:run
./demo.sh                                          # in another terminal
```

H2 in memory, outbox rows logged rather than sent. Everything in
[README.md](README.md) works; nothing is durable across a restart.

With Docker, for the PostgreSQL behaviour the tests cannot reach — real
migrations, `SELECT ... FOR UPDATE`, `SET lock_timeout`:

```bash
docker compose up --build
./demo.sh
docker compose down -v
```

[`compose.yaml`](compose.yaml) pins `SPRING_PROFILES_ACTIVE=postgres`, which is
not optional: without it the container runs its default profile, which is H2 in
memory, and the whole stack appears to work perfectly while storing nothing.

## 3. The async half alone

```bash
sam build
sam deploy --stack-name flight-ops-lambda --resolve-s3 --capabilities CAPABILITY_IAM
```

Two minutes. Creates the queue, the DLQ, the DynamoDB table and the Lambda.
Then point a locally-running service at it:

```bash
SQS_QUEUE_URL=$(aws cloudformation describe-stacks --stack-name flight-ops-lambda \
  --query "Stacks[0].Outputs[?OutputKey=='QueueUrl'].OutputValue" --output text) \
APP_EVENTS_PUBLISHER=sqs \
./mvnw spring-boot:run
```

Make a booking and watch it arrive on the other side:

```bash
sam logs -n booking-event-handler --stack-name flight-ops-lambda --tail
aws dynamodb scan --table-name <TableName from the outputs> --select COUNT
```

The log line carries the `traceparent` of the HTTP request that made the
booking, which is how one request is followed across a process boundary and a
queue. Delete it with `sam delete --stack-name flight-ops-lambda`, or with
[`deploy/aws/down.sh`](deploy/aws/down.sh), which also removes the S3 bucket
`--resolve-s3` quietly created.

## 4. The full thing on AWS

Detailed runbook: [deploy/aws/README.md](deploy/aws/README.md). The short version:

```bash
brew install awscli eksctl kubernetes-cli helm aws-sam-cli
aws configure                                   # region ap-south-1
aws sts get-caller-identity                     # must print your account id

ALERT_EMAIL=you@example.com ./deploy/aws/up.sh  # ~50 minutes
```

Twelve steps. Step 1 prints the cost table and asks you to type `yes`; nothing
before that costs anything. Steps 2–9 build the infrastructure. Step 10 stops
and prints four values for the GitHub repository settings:

| | | |
|---|---|---|
| Secret | `AWS_ACCOUNT_ID` | your account id |
| Variable | `DEPLOY_ENABLED` | `true` |
| Variable | `SQS_QUEUE_URL` | from the SAM stack |
| Variable | `DB_URL` | from the data stack |

Set those, run the workflow, and the script picks up: it waits for the rollout,
creates the Ingress, waits for the load balancer, and finishes by running
`demo.sh` against the public URL.

**CI deploys the application; the script does not.** The image tag is the commit
SHA, and the only thing that knows the commit SHA is the thing that built the
image — a laptop build would tag whatever happened to be checked out, including
uncommitted work. It also means no password reaches GitHub: `up.sh` writes the
generated passwords straight into a Kubernetes Secret, and CI applies a
Deployment that references it by name.

Every step checks whether its resource exists before creating it, so an
interrupted run is resumed by running the same command again.

### The order, and why it is that order

1. **Foundation** (ECR, GitHub OIDC, CI role, budgets) — CI cannot push an image
   to a registry that does not exist, and the budgets should be alerting before
   anything expensive is created.
2. **SAM** (queue, table, Lambda) — cheap, independent of the cluster, and its
   queue URL is an input the cluster's ConfigMap needs.
3. **EKS** (~20 min) — the long one.
4. **Access entry** — CI gets `AmazonEKSEditPolicy` scoped to one namespace, via
   the API rather than the `aws-auth` ConfigMap. A malformed ConfigMap edit locks
   every principal out of the cluster at once, including the one that would fix it.
5. **RDS** (~10 min) — needs eksctl's VPC and subnets, so it cannot be earlier.
6. **IRSA, load balancer controller, metrics-server** — cluster setup that
   happens once rather than per deploy.
7. **Namespace and Secret** — generated passwords, bcrypt-hashed, never on disk.

## 5. What it costs

`ap-south-1`, on-demand, from the AWS price list on 22 September 2026.

| | rate | per day |
|---|---|---|
| EKS control plane | $0.10/hr | $2.40 |
| 2 × t3.medium | $0.0448/hr each | $2.15 |
| NAT gateway | $0.056/hr + $0.056/GB | $1.43 |
| Application Load Balancer | $0.0239/hr + LCU | $0.62 |
| RDS db.t4g.micro + 20 GB gp3 | $0.021/hr | $0.50 |
| EBS (2 × 20 GB gp3), public IPv4 | | $0.62 |
| SQS, Lambda, DynamoDB, ECR | free tier at this volume | $0.00 |
| | | **$7.72** |

Assumes 1.5 GB/day through the NAT gateway, about 0.25 LCU on the load
balancer, control-plane logging off, and application logs not shipped to
CloudWatch.

### 7, 10 and 15 days

AWS invoices India in INR with 18% GST. At ₹95.8 to the dollar:

| Running for | USD | with GST | approx INR |
|---|---|---|---|
| 7 days | $54 | $64 | ₹6,100 |
| 10 days | $77 | $91 | ₹8,700 |
| 15 days | $116 | $137 | ₹13,100 |
| **30 days** | **$232** | **$273** | **₹26,200** |

The last row is not an option anyone chooses. It is what happens when the
teardown is put off until after the weekend. Creating this takes 50 minutes and
deleting it takes 20; the reason the 30-day row is in this table is that the
gap between those two numbers is not what decides the bill.

`up.sh` creates two budgets — $60/month alerting at 50/80/100%, $12/day alerting
at 80% — and they send e-mail. E-mail is not a brake. **Set a calendar reminder
for the teardown date before you create anything.**

### Cheaper shapes

| | What changes | $/day | 15 days with GST |
|---|---|---|---|
| **A. As designed** | — | 7.72 | $137 |
| A′. Public subnets | `privateNetworking: false` in `cluster.yaml`; no NAT gateway; nodes get public IPs | 6.42 | $114 |
| B. EKS Fargate | no node group; CoreDNS and the controller also move to Fargate; more setup | 7.12 | $126 |
| **C. One EC2 t3.small** | `compose.yaml` on a single instance, plus the SAM stack. No EKS, no ALB, no RDS | **0.80** | **$14** |
| D. Prepare only | nothing created | 0 | $0 |

C is the one to take seriously if the goal is a live URL rather than a
Kubernetes demonstration: a t3.small at $0.0224/hour running `docker compose up`
with an Elastic IP, plus the free-tier SAM stack. It proves the service works on
AWS and proves nothing about EKS. Ninety per cent of the cost of shape A is the
Kubernetes story, and whether that is worth $123 over fifteen days depends
entirely on who is asking.

B saves $0.60/day and costs an afternoon: Fargate profiles for `kube-system`,
CoreDNS patched off its EC2-only annotation, and the load balancer controller
needing somewhere to run. Not worth it for a two-week window.

### What it costs if you never delete it

| | per month |
|---|---|
| EKS control plane | $73 |
| 2 × t3.medium | $65 |
| NAT gateway | $43 |
| Application Load Balancer | $19 |
| RDS db.t4g.micro | $15 |
| EBS, IPv4 | $19 |
| | **$232** ($273 with GST) |

And the three that outlive a botched teardown, silently, because nothing points
at them any more: an orphaned ALB at $19/month, an unassociated Elastic IP at
$3.60/month each, an unattached EBS volume at $1.80/month per 20 GB.
[`down.sh`](deploy/aws/down.sh) checks for all three by name.

### Watching it

```bash
./deploy/aws/cost-check.sh        # daily by service, month to date, forecast
```

Cost Explorer lags 8–24 hours, so today's figure is always incomplete. Read the
forecast, not today's total.

## 6. Tearing it down

```bash
./deploy/aws/down.sh
```

Type `delete`. About 20 minutes. Then thirteen checks run — load balancers both
kinds, clusters, instances, NAT gateways, volumes, Elastic IPs, RDS instances
and snapshots, stacks, log groups, secrets, ECR, and a catch-all query for
anything tagged `Project=flight-ops` — and **the script exits non-zero if any of
them finds something.** That exit code is the answer to "is it gone"; the
deletes themselves are not, because a CloudFormation stack can delete
successfully while leaving a load balancer behind.

Two orderings are load-bearing:

- **The Ingress is deleted first.** Delete the namespace with an Ingress in it
  and the Kubernetes object disappears while the controller — being deleted at
  the same moment — never receives the event that would delete the ALB. The
  load balancer survives, attached to nothing, at $19/month, until someone
  finds it in the console.
- **The database is deleted before the cluster.** Its security group is in
  eksctl's VPC and the VPC delete blocks on it. eksctl then fails after twenty
  minutes with a message about a dependency it does not name.

If a check fails, run the script again — most failures are ordering and a second
pass succeeds. Two things it cannot prove: Cost Explorer lags, so verify the
next day and expect zero rather than "small"; and data transferred earlier in
the month is still billed at month end. Deleted is not refunded.

## 7. What breaks first

Under load, in the order it happens:

1. **Database connections.** Four pods × a Hikari pool of 10 = 40 connections
   against db.t4g.micro's ~112. That is why `k8s/base/hpa.yaml` caps
   `maxReplicas` at 4 rather than at something the cluster could hold. Raise the
   instance class before raising the replica count, or the symptom is
   "remaining connection slots are reserved", which reads like a database fault
   and is a replica-count fault.
2. **Seat lock contention.** Concurrent bookings for the *same flight* serialise
   behind `SELECT ... FOR UPDATE` — that queueing is the oversell guarantee, not
   a bug. `SET lock_timeout = '3s'` bounds the wait; past it, requests get 503
   with `Retry-After`. Different flights do not contend at all, so this scales
   with per-flight concurrency and not with total traffic.
3. **Outbox drain rate.** One publisher polls every second and claims up to 100
   rows with `FOR UPDATE SKIP LOCKED`, so the ceiling is roughly 100 events per
   second per replica. `outbox.pending` is the gauge that shows the backlog
   before anyone notices downstream.
4. **Lambda reserved concurrency**, capped at 10, with the trade-off spelled out
   in `template.yaml`: throttled SQS messages return to the queue with their
   receive count incremented, so a long backlog can push good messages into the
   DLQ. Raise `maxReceiveCount` or use `MaximumConcurrency` on the event source
   before raising traffic.
5. **Node IP addresses, not CPU.** With the VPC CNI each pod takes a real VPC IP
   and a t3.medium caps at 17 pods. Two nodes hold the HPA's ceiling
   comfortably; a larger ceiling needs prefix delegation or bigger nodes.

## 8. HTTP, and what HTTPS would take

The Ingress listens on port 80. No TLS. Traffic between a browser and the load
balancer is plaintext, which includes the HTTP Basic credentials — they are
base64, which is encoding rather than encryption.

That is acceptable for exactly one situation: a short-lived demonstration, with
generated throwaway passwords, containing no real data. It is not acceptable for
anything else, and the alternative was a self-signed certificate, which trains
people to click through the browser warning that exists to stop them.

TLS needs a certificate, a certificate needs a domain, and a domain is a
purchase this repository cannot make on anyone's behalf. With one:

1. Request a certificate in ACM for `api.example.com`, in `ap-south-1` —
   regional, because an ALB cannot use a `us-east-1` certificate the way
   CloudFront can.
2. Validate it by DNS. Route 53 does this in one click; another registrar means
   adding a CNAME by hand.
3. Add three annotations to `k8s/components/ingress/ingress.yaml`:
   ```yaml
   alb.ingress.kubernetes.io/listen-ports: '[{"HTTP":80},{"HTTPS":443}]'
   alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:ap-south-1:…:certificate/…
   alb.ingress.kubernetes.io/ssl-redirect: '443'
   ```
4. Point the domain at the ALB — an ALIAS record in Route 53, or a CNAME
   elsewhere.
5. Add `server.forward-headers-strategy: framework` so the application sees the
   original scheme rather than the load balancer's, and its redirects and
   generated links stay on `https`.

ACM certificates are free, and the ALB costs the same either way. The whole
change is about ten minutes plus DNS propagation, and the reason it is written
here rather than done is the domain.
