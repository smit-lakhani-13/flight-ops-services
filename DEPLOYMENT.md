# Deployment

There are three ways to run this service, costing $0, about $0 and $7.72 a
day. This document gives the commands for each one, prices the third, and shows
how to create it and, more importantly, how to destroy it.

**Executed on: —**

That line is blank because I have never run any of this against a real AWS
account. The templates lint, the scripts pass shellcheck and parse, and the
manifests validate against Kubernetes 1.36. None of that proves they work. When
it is run, this line gets the dates, and a new last section, Evidence, gets the
command output.

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
| **Full stack on EKS** | all of that plus rolling deploys, IRSA, HPA, a public URL | an AWS account, five CLIs, 50 minutes | $7.72/day |

The middle one is underrated. It is the interesting half of the architecture:
the transactional outbox crossing a real queue into a real consumer. It also
costs nothing. SQS gives a million requests a month free forever, DynamoDB
on-demand bills per write with no hourly charge, and an idle Lambda costs
nothing at all. The EKS half is where the money goes. What it adds is
Kubernetes, and it says nothing new about this system's design.

[§5](#5-what-it-costs) also prices cheaper variants of the third shape. Public
subnets with no NAT gateway cost $6.42/day. A single EC2 instance running
`compose.yaml`, with the SAM stack beside it, costs $0.80/day.

## 2. Localhost

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21      # or wherever your JDK 21 is
./mvnw spring-boot:run
./demo.sh                                          # in another terminal
```

This runs on H2 in memory, and the outbox logs its rows instead of sending
them. Everything in [README.md](README.md) works, but nothing survives a
restart.

With Docker you also get the PostgreSQL behaviour that the H2 profile cannot
reach: real migrations, `SELECT ... FOR UPDATE` and `SET lock_timeout`.

```bash
docker compose up --build
./demo.sh
docker compose down -v
```

[`compose.yaml`](compose.yaml) pins `SPRING_PROFILES_ACTIVE=postgres`, and it
has to. Without it the container runs its default profile, which is H2 in
memory, and the whole stack appears to work while storing nothing.

## 3. The async half alone

```bash
sam build
sam deploy --stack-name flight-ops-lambda --resolve-s3 --capabilities CAPABILITY_IAM
```

This takes two minutes and creates the queue, the DLQ, the DynamoDB table and
the Lambda. Then point a locally running service at it:

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
booking. With it, one request can be followed across a process boundary and a
queue.

Delete the stack with `sam delete --stack-name flight-ops-lambda`, or with
[`deploy/aws/down.sh`](deploy/aws/down.sh). Neither removes the
`aws-sam-cli-managed-default` bucket that `--resolve-s3` created, because every
SAM project in the account and region shares it. `down.sh --delete-sam-bucket`
removes it once you are sure nothing else uses it.

## 4. The full thing on AWS

The detailed runbook is [deploy/aws/README.md](deploy/aws/README.md). The short
version:

```bash
brew install awscli eksctl kubernetes-cli helm aws-sam-cli gettext
aws configure                                   # region ap-south-1
aws sts get-caller-identity                     # must print your account id

ALERT_EMAIL=you@example.com ./deploy/aws/up.sh  # ~50 minutes
```

`gettext` provides `envsubst`, which the preflight in `up.sh` checks for and
macOS does not ship.

`up.sh` runs in twelve steps. Step 1 prints the cost table and asks you to type
`yes`, and nothing before that costs anything. Steps 2 to 9 build the
infrastructure. Step 10 stops and prints the values to set in the GitHub
repository settings:

| | | |
|---|---|---|
| Secret | `AWS_ACCOUNT_ID` | your account id |
| Variable | `DEPLOY_ENABLED` | `true` |
| Variable | `SQS_QUEUE_URL` | from the SAM stack |
| Variable | `DB_URL` | from the data stack |

Set those and run the workflow. The script then waits for the rollout, creates
the Ingress, waits for the load balancer, and finishes by running `demo.sh`
against the public URL.

CI deploys the application, and the script does not. The image tag is the
commit SHA, and only the job that built the image knows it. A laptop build
would tag whatever happened to be checked out, including uncommitted work. The
split also keeps every password away from GitHub. `up.sh` writes the generated
passwords straight into a Kubernetes Secret, and CI applies a Deployment that
refers to it by name.

Every step checks whether its resource exists before creating it. To resume an
interrupted run, run the same command again.

### The order, and why it is that order

1. **Foundation** (ECR, GitHub OIDC, CI role, budgets). CI cannot push an image
   to a registry that does not exist, and the budgets should be alerting before
   anything expensive is created.
2. **SAM** (queue, table, Lambda). It is cheap and independent of the cluster,
   and the cluster's ConfigMap needs its queue URL.
3. **EKS** (~20 min). The long one.
4. **Access entry.** CI gets `AmazonEKSEditPolicy` scoped to one namespace,
   through the access-entry API. I kept away from the `aws-auth` ConfigMap,
   because one malformed edit to it locks every principal out of the cluster at
   once, including the one that would fix it.
5. **RDS** (~10 min). It needs eksctl's VPC and subnets, so it cannot come
   earlier.
6. **IRSA, load balancer controller, metrics-server.** Cluster setup that
   happens once. No deploy repeats it.
7. **Namespace and Secret.** Generated passwords, never written to disk. The API
   and ops passwords are bcrypt-hashed. The database password is stored as it
   is, because the JDBC driver needs it.

## 5. What it costs

Prices are for `ap-south-1`, on-demand, from the AWS price list on 22 September
2026.

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

The figures assume 1.5 GB/day through the NAT gateway, about 0.25 LCU on the
load balancer, control-plane logging off, and application logs kept out of
CloudWatch.

### 7, 10 and 15 days

AWS invoices India in INR with 18% GST. At ₹95.8 to the dollar:

| Running for | USD | with GST | approx INR |
|---|---|---|---|
| 7 days | $54 | $64 | ₹6,100 |
| 10 days | $77 | $91 | ₹8,700 |
| 15 days | $116 | $137 | ₹13,100 |
| **30 days** | **$232** | **$273** | **₹26,200** |

Nobody chooses the last row. It is what happens when the teardown is put off
until after the weekend. Creating the stack takes 50 minutes and deleting it
takes 20, and neither number decides the bill. Whether someone remembers the
teardown does, which is why the 30-day row is here.

`up.sh` creates two budgets: $60/month, alerting at 50/80/100%, and $12/day,
alerting at 80%. They send e-mail, and an e-mail does not stop anything. **Set a
calendar reminder for the teardown date before you create anything.**

### Cheaper shapes

| | What changes | $/day | 15 days with GST |
|---|---|---|---|
| **A. As designed** | nothing | 7.72 | $137 |
| A′. Public subnets | `privateNetworking: false` in `cluster.yaml`; no NAT gateway; nodes get public IPs | 6.42 | $114 |
| B. EKS Fargate | no node group; CoreDNS and the controller also move to Fargate; more setup | 7.12 | $126 |
| **C. One EC2 t3.small** | `compose.yaml` on a single instance, plus the SAM stack. No EKS, no ALB, no RDS | **0.80** | **$14** |
| D. Prepare only | nothing created | 0 | $0 |

If the goal is a live URL, C is the one to take seriously. It is a t3.small at
$0.0224/hour running `docker compose up` with an Elastic IP, plus the free-tier
SAM stack. It proves the service works on AWS and proves nothing about EKS.
Ninety per cent of the cost of shape A pays for Kubernetes. Whether that is
worth $123 over fifteen days depends on who is asking.

B saves $0.60/day and costs an afternoon: Fargate profiles for `kube-system`,
CoreDNS patched off its EC2-only annotation, and somewhere for the load
balancer controller to run. For a two-week window I judged it not worth the
work.

### What it costs if you never delete it

| | per month |
|---|---|
| EKS control plane | $73 |
| 2 × t3.medium | $65 |
| NAT gateway | $43 |
| Application Load Balancer | $19 |
| RDS db.t4g.micro | $15 |
| EBS, IPv4 | $19 |
| | **$234** ($276 with GST) |

These are 730-hour months, which is why the total is a little above the 30-day
row in the table before.

Some resources outlive a botched teardown without any error, because nothing
points at them any more. An orphaned ALB costs $19/month, an unassociated
Elastic IP $3.60/month each, and an unattached EBS volume $1.80/month per 20 GB.
The final sweep in [`down.sh`](deploy/aws/down.sh) checks for all three: the
load balancer by name, and the Elastic IPs and volumes by the
`Project=flight-ops` tag.

### Watching it

```bash
./deploy/aws/cost-check.sh        # daily by service, month to date, forecast
```

Cost Explorer lags by 8 to 24 hours, so today's figure is always incomplete.
Read the forecast instead of today's total.

## 6. Tearing it down

```bash
./deploy/aws/down.sh
```

Type `delete` when it asks. The deletes take about 20 minutes. Then the script
runs fourteen checks and exits non-zero if any of them finds something. They
cover both kinds of load balancer, clusters, instances, NAT gateways, volumes,
Elastic IPs, RDS instances and snapshots, stacks, log groups, secrets and ECR,
plus a catch-all query for anything tagged `Project=flight-ops`. With
`--keep-foundation` there are thirteen, because that flag leaves the ECR
repository behind and skips its check.

The exit code answers "is it gone". The deletes themselves cannot, because a
CloudFormation stack can delete successfully and still leave a load balancer
behind.

The order matters:

- **Delete the Ingress first.** If you delete the namespace with the Ingress
  still in it, the controller is removed at the same time and never sees the
  event that would delete the ALB. The load balancer stays up, attached to
  nothing, at $19/month, until someone finds it in the console.

- **Database before the cluster.** Its security group is in eksctl's
  VPC, and the VPC delete blocks on it. eksctl then fails after twenty minutes
  with a message about a dependency it does not name.

If a check fails, run the script again. Most failures are about ordering, and a
second pass succeeds.

The checks say nothing about the bill. Cost Explorer lags, so look again the
next day and expect zero, not "small". Data transferred earlier in the month is
still billed at month end, because deleting a resource refunds nothing.

## 7. What breaks first

Under load, in the order it happens:

1. **Database connections.** Four pods × a Hikari pool of 10 = 40 connections,
   against db.t4g.micro's ~112. So `k8s/base/hpa.yaml` caps `maxReplicas` at 4,
   well below what the cluster could hold. Raise the instance class before the
   replica count. Otherwise the symptom is "remaining connection slots are
   reserved", which looks like a database fault but comes from the replica
   count.

2. **Seat lock contention.** Concurrent bookings for the *same flight* queue
   behind `SELECT ... FOR UPDATE`. That queue is what prevents overselling, so
   it is expected. `SET lock_timeout = '3s'` bounds the wait, and after that a
   request gets 503 with `Retry-After`. Different flights never contend, so this
   limit depends on concurrency per flight and not on total traffic.

3. **Outbox drain rate.** One publisher polls every second and claims up to 100
   rows with `FOR UPDATE SKIP LOCKED`, so the ceiling is roughly 100 events per
   second per replica. The `outbox.pending` gauge shows the backlog before
   anyone notices it downstream.

4. **Lambda reserved concurrency**, capped at 10. `template.yaml` explains the
   trade-off. Throttled SQS messages go back to the queue with their receive
   count raised, so a long backlog can push good messages into the DLQ. Before
   raising traffic, raise `maxReceiveCount` or set `MaximumConcurrency` on the
   event source.

5. **Node IP addresses, not CPU.** With the VPC CNI each pod takes a real VPC
   IP, and a t3.medium holds at most 17 pods. Two nodes hold the HPA's ceiling
   easily. A higher ceiling needs prefix delegation or bigger nodes.

## 8. HTTP, and what HTTPS would take

The Ingress listens on port 80, with no TLS. Traffic between a browser and the
load balancer is plaintext, and that includes the HTTP Basic credentials. They
are only base64-encoded, and base64 is not encryption.

I accept that for one case only: a short-lived demonstration with generated
throwaway passwords and no real data. It is not acceptable for anything else. I
rejected a self-signed certificate, because it trains people to click through
the browser warning that exists to stop them.

TLS needs a certificate, and a certificate needs a domain. A domain is a
purchase this repository cannot make on anyone's behalf. With one, the steps
are:

1. Request a certificate in ACM for `api.example.com`, in `ap-south-1`. It has
   to be regional, because an ALB cannot use a `us-east-1` certificate the way
   CloudFront can.
2. Validate it by DNS. Route 53 does this in one click. With another registrar
   you add a CNAME by hand.
3. Add these annotations to `k8s/components/ingress/ingress.yaml`:
   ```yaml
   alb.ingress.kubernetes.io/listen-ports: '[{"HTTP":80},{"HTTPS":443}]'
   alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:ap-south-1:…:certificate/…
   alb.ingress.kubernetes.io/ssl-redirect: '443'
   ```
4. Point the domain at the ALB: an ALIAS record in Route 53, or a CNAME
   elsewhere.
5. Add `server.forward-headers-strategy: framework`, so the application sees
   the original scheme instead of the load balancer's, and its redirects and
   generated links stay on `https`.

ACM certificates are free, and the ALB costs the same either way. The change
takes about ten minutes plus DNS propagation. The only thing stopping me is the
domain.
