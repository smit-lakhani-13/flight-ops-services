# Deployment

There are three ways to run this service, costing $0, about $0 and $7.72 a
day. This document gives the commands for each one, prices the third, and shows
how to create it and, more importantly, how to destroy it.

**Executed on: —**

That line is blank because I have never run any of this against a real AWS
account. The templates lint, the scripts pass shellcheck and a self-test
against stubbed tools, and the manifests validate against Kubernetes 1.36. None
of that proves they work. When it is run, this line gets the dates, and a new
last section, Evidence, gets the command output.

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
| **Full stack on EKS** | all of that plus rolling deploys, IRSA, HPA, a public URL | an AWS account, five CLIs, JDK 21, 50 minutes | $7.72/day |

The middle one is underrated. It is the interesting half of the architecture:
the transactional outbox crossing a real queue into a real consumer. It also
costs nothing. SQS gives a million requests a month free forever, DynamoDB
on-demand bills per write with no hourly charge, and an idle Lambda costs
nothing at all. The two CloudWatch alarms on the queues fit inside the ten
standard alarms CloudWatch does not charge for, unless the account already
uses them. The EKS half is where the money goes. What it adds is Kubernetes,
and it says nothing new about this system's design.

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

With Docker you also get what the H2 profile lacks: Flyway migrations, and
PostgreSQL's own row locks and `lock_timeout`.

```bash
docker compose up --build
./demo.sh
docker compose down -v
```

[`compose.yaml`](compose.yaml) sets `SPRING_PROFILES_ACTIVE=postgres`: the
local database, Flyway and the log publisher. The image itself defaults to
`prod`, which expects RDS, IRSA and the SQS publisher and has no default
passwords. Run bare, with no `DB_URL`, it stops at startup with
`'url' must start with "jdbc"`. It never falls back to H2 and the `{noop}` dev
passwords. The deploy job checks for that failure before it pushes an image, in
the step "The image will not start without a database". The `image` job runs
the same check on every push or pull request to `main`.

## 3. The async half alone

```bash
export AWS_REGION=ap-south-1 AWS_DEFAULT_REGION=ap-south-1   # what deploy/aws/lib.sh pins
rm -rf .aws-sam
./mvnw -B -q -f lambda/pom.xml clean package
sam deploy \
  --template-file template.yaml \
  --stack-name flight-ops-lambda \
  --resolve-s3 \
  --capabilities CAPABILITY_IAM \
  --no-confirm-changeset \
  --no-fail-on-empty-changeset \
  --tags Project=flight-ops
```

These are the commands step 3 of `up.sh` runs, in the region
`deploy/aws/lib.sh` pins, and they need JDK 21. Maven
builds the jar, and `sam build` is not used. SAM builds in a scratch copy of
the `CodeUri` directory, where the Lambda tests cannot find `../events` and
`../contracts`. So `template.yaml` points `CodeUri` at the shaded jar,
`lambda/target/booking-event-handler.jar`. `sam deploy` prefers the template
in `.aws-sam/build` when one exists, so the first line removes a stale build
left by an earlier `sam build`.

This takes two minutes and creates the queue, the DLQ, two CloudWatch alarms
on them with no notification target, the DynamoDB table and the Lambda. Then
point a locally running service at it:

```bash
SQS_QUEUE_URL=$(aws cloudformation describe-stacks --stack-name flight-ops-lambda \
  --query "Stacks[0].Outputs[?OutputKey=='QueueUrl'].OutputValue" --output text) \
APP_EVENTS_PUBLISHER=sqs \
./mvnw spring-boot:run
```

Make a booking and watch it arrive on the other side:

```bash
sam logs -n BookingEventFunction --stack-name flight-ops-lambda --tail
aws dynamodb scan --table-name <TableName from the outputs> --select COUNT
```

The log line carries the `traceparent` of the HTTP request that made the
booking. With it, one request can be followed across a process boundary and a
queue.

Delete the stack with
`sam delete --stack-name flight-ops-lambda --region ap-south-1`, or with
[`deploy/aws/down.sh`](deploy/aws/down.sh). Neither removes the
`aws-sam-cli-managed-default` bucket that `--resolve-s3` created, because every
SAM project in the account and region shares it. `down.sh --delete-sam-bucket`
removes it once you are sure nothing else uses it.

## 4. The full thing on AWS

The detailed runbook is [deploy/aws/README.md](deploy/aws/README.md). The short
version:

```bash
brew install awscli eksctl kubernetes-cli helm aws-sam-cli openjdk@21
aws configure                                   # region ap-south-1
aws sts get-caller-identity                     # must print your account id

ALERT_EMAIL=you@example.com ./deploy/aws/up.sh  # ~50 minutes
```

The preflight checks the tools, the credentials and `./mvnw -v`, which must
report JDK 21 because the enforcer rule in `lambda/pom.xml` accepts nothing
else. No system Maven is needed. `gettext`, which provides `envsubst`, is
needed only to run `deploy/aws/render-aws.sh` by hand.

`up.sh` runs in twelve steps. Step 1 prints the cost table and asks you to type
`yes`, and nothing before that costs anything. Steps 2 to 8 build the
infrastructure, and the run then stops twice for you:

- Step 9 prints the generated API and ops passwords and waits for you to type
  `saved`. The closing summary repeats them. The cluster holds only their
  bcrypt hashes. A re-run that finds the Secret already there skips this.
- Step 10 prints the values to set in the GitHub repository settings, and waits
  for `done`.

| | | |
|---|---|---|
| Secret | `AWS_ACCOUNT_ID` | your account id |
| Variable | `DEPLOY_ENABLED` | `true` |
| Variable | `SQS_QUEUE_URL` | from the SAM stack |
| Variable | `DB_URL` | from the data stack |

Set those, run the workflow or push to `main`, then type `done`. The script
waits up to 30 minutes for CI to create `deployment/flight-ops`, then up to 20
for it to become available. The deploy job is skipped unless `DEPLOY_ENABLED`
is `true` and the run is on `main`, so a missing variable shows up as the
30-minute wait running out. The script then creates the Ingress, waits for the
load balancer, and finishes by running `demo.sh` against the public URL. It
skips `demo.sh` when the Secret came from an earlier run, because it no longer
knows the passwords.

CI deploys the application, and the script does not. The image tag is the
commit SHA, and only the job that built the image knows it. A laptop build
would tag whatever happened to be checked out, including uncommitted work. The
split also keeps every password away from GitHub. `up.sh` writes the generated
passwords straight into a Kubernetes Secret, and CI applies a Deployment that
refers to it by name.

Before it builds, the deploy job asks ECR whether the commit's image is already
there, in the step "Is this commit already in ECR?". It runs
[`deploy/aws/ecr-image-exists.sh`](deploy/aws/ecr-image-exists.sh), which calls
`ecr:DescribeImages` and reports the image missing only on
`ImageNotFoundException`. Any other error fails the job. Guessing "missing"
would rebuild the image and then fail at the push, because the repository's
tags are immutable.

Every step checks whether its resource exists before creating it. To resume an
interrupted run, run the same command again. If eksctl stopped part way through
step 4, the re-run finishes the cluster. It waits for the control plane, then
creates whichever of the vpc-cni, kube-proxy and coredns addons, the cluster's
IAM OIDC provider and the `ng-1` node group is missing. Step 1 asks for eksctl
0.184.0 or later, because older releases install those addons self-managed and
the re-run looks them up as EKS addons.

Two cases still need a hand. A re-run in the first minutes of step 4, before
EKS lists the cluster, calls `eksctl create cluster` a second time, and that
most likely fails on the existing CloudFormation stack. Wait for the stack to
settle, then re-run. The database password exists only in the shell from step 6
until step 9 writes the Secret. A run that stops in between stops again at
step 9, and the message says how to set a new password.

### The order, and why it is that order

The numbers are `up.sh`'s own steps. Step 1 is the preflight and the cost
table, and steps 10 to 12 are the hand-off to CI, the Ingress and the demo
described above.

2. **Foundation** (ECR, GitHub OIDC, CI role, budgets). CI cannot push an image
   to a registry that does not exist, and the budgets should be alerting before
   anything expensive is created.
3. **SAM** (queue, table, Lambda). It is cheap and independent of the cluster,
   and the cluster's ConfigMap needs its queue URL.
4. **EKS** (~20 min). The long one.
5. **Access entry.** CI gets `AmazonEKSEditPolicy` scoped to one namespace,
   through the access-entry API. I kept away from the `aws-auth` ConfigMap,
   because one malformed edit to it locks every principal out of the cluster at
   once, including the one that would fix it. The associate call's exit code
   cannot tell "already associated" from "refused". So this step reads the
   association back and stops unless the policy is scoped to
   `namespace/flight-ops`.
6. **RDS** (~10 min). It needs eksctl's VPC and subnets, so it cannot come
   earlier. A data stack in `CREATE_COMPLETE`, `UPDATE_COMPLETE` or
   `UPDATE_ROLLBACK_COMPLETE` counts as existing, and its password is left
   alone. `ROLLBACK_COMPLETE` or `DELETE_FAILED` stops the run with the
   commands that delete the stack. Any `*_IN_PROGRESS` state stops it and
   tells you to wait for the stack to finish, then re-run. A status read that
   fails for any reason but "does not exist" stops it too, because a throttled
   call taken for "no stack" would redeploy it with a new password. So does a
   `JdbcUrl` output that does not start with `jdbc:postgresql://`.
7. **IRSA.** The pods' AWS identity, with no access keys. Cluster setup that
   happens once. No deploy repeats it.
8. **Load balancer controller and metrics-server.** Also set up once, and no
   deploy repeats it.
9. **Namespace and Secret.** Generated passwords, never written to disk. The API
   and ops passwords are bcrypt-hashed. The database password is stored as it
   is, because the JDBC driver needs it.

### The manifests

| Path | What it holds |
|---|---|
| `k8s/base/` | `configmap`, `serviceaccount`, `deployment`, `service`, `hpa` and `pdb`: everything true in any environment |
| `k8s/overlays/aws/` | the ECR image, the IRSA role annotation, the queue URL and the database URL |
| `k8s/components/ingress/` | the Ingress, and so the ALB. It is separate because applying it starts a continuous charge |
| `k8s/namespace.yaml` | cluster-scoped, so CI's namespace-scoped role cannot apply it. `up.sh` does |
| `k8s/secret.example.yaml` | a template. `up.sh` generates the real Secret and never writes it to disk |

CI renders the overlay with
`AWS_ACCOUNT_ID=… IMAGE_TAG=… SQS_QUEUE_URL=… DB_URL=… ./deploy/aws/render-aws.sh`,
and a person can run the same command. It exits 2 if any of the four is unset
or empty, and 3 if a `${…}` placeholder survives substitution. So a missing
value is a failed command, and never a manifest holding the literal
`${DB_URL}`.

The pod has three probes. The `startupProbe` allows up to 30 × 10s for the JVM,
Spring and Flyway to start, so the liveness timeout can stay tight. The
`readinessProbe` on `/actuator/health/readiness` gates traffic. The
`livenessProbe` on `/actuator/health/liveness` restarts a wedged container.

The rolling update sets `maxUnavailable: 0`, and a `PodDisruptionBudget` covers
voluntary disruptions such as a node drain. A rollout and a drain are different
events, so each has its own guard.

The heap is `-XX:MaxRAMPercentage=75.0`, so it follows the container's 768Mi
memory limit, and going over that limit gets the container OOMKilled. There is
no CPU limit. CFS throttling hits a JVM hardest during class loading and GC,
inside the startup probe's window. It would also make the HPA measure the
throttle instead of the load.

Shutdown is a 5s `preStop` sleep plus a 30s
`spring.lifecycle.timeout-per-shutdown-phase`. That is 35s, inside the 45s
`terminationGracePeriodSeconds`. Get that inequality backwards and the kubelet
sends SIGKILL mid-request. I pinned the 30s in `application.yml`, because the
manifest comment does arithmetic on it.

The Dockerfile's `ENTRYPOINT` is `sh -c "exec java …"`. `exec` makes the JVM
PID 1, so it receives SIGTERM. Without it the shell is PID 1 and forwards
nothing, and the pod dies by SIGKILL at the end of the grace period.

The Dockerfile says `USER 1001:1001` and the pod says `runAsUser: 1001`. The
kubelet verifies non-root against `runAsUser` when the pod sets it. A pod
without it is checked against the user in the image config, and the kubelet
cannot resolve a name there. An image with `USER spring` fails such a pod with
`CreateContainerConfigError`.

`readOnlyRootFilesystem: true` comes with a `medium: Memory` `emptyDir` at
`/tmp`, with `sizeLimit: 64Mi`. Tomcat's work directory and `hsperfdata` both
need `/tmp`. Code running inside the container cannot overwrite the jar or
leave anything that outlives the pod. It can still write a binary to `/tmp`
and run it, because an `emptyDir` has no `noexec` option.

`podAntiAffinity` is `preferred`. Two replicas on one node make one node loss a
full outage, and a PDB does nothing about a node dying. `required` would leave
a pod `Pending` for ever on a single-node cluster.

The HPA scales on CPU, because `metrics-server` offers CPU. The slow path here
waits on `SELECT … FOR UPDATE`, which may not show as CPU. The better signal is
request rate or queue depth, through KEDA or the Prometheus adapter.
`/actuator/prometheus` already serves, because `micrometer-registry-prometheus`
is on the classpath, so the adapter is the missing piece.

A Kubernetes Secret is base64-encoded and unencrypted, as
`secret.example.yaml` says. The production answer is Secrets Manager through
the Secrets Store CSI driver.

## 5. What it costs

Prices are for `ap-south-1`, on-demand, from the AWS price list on 22 September
2026.

| | rate | per day |
|---|---|---|
| EKS control plane | $0.10/hr | $2.40 |
| 2 × t3.medium | $0.0448/hr each | $2.15 |
| NAT gateway | $0.056/hr + $0.056/GB | $1.43 |
| Application Load Balancer | $0.0239/hr + LCU | $0.62 |
| RDS db.t4g.micro (instance hours) | $0.021/hr | $0.50 |
| EBS (2 × 20 GB gp3), public IPv4 | | $0.62 |
| SQS, Lambda, DynamoDB, X-Ray, two CloudWatch alarms | free tier at this volume; an account's first ten standard alarms are free | $0.00 |
| ECR, under 1 GB of images | $0.10/GB-month after any free tier | $0.00 |
| | | **$7.72** |

The figures assume 1.5 GB/day through the NAT gateway, about 0.25 LCU on the
load balancer, control-plane logging off, and application logs kept out of
CloudWatch. The RDS row leaves out its 20 GB of gp3 storage, which adds under
$0.10 a day.

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

`up.sh` creates two budgets: $60/month, alerting at 50/80/100% of actual
spend and when the forecast passes 100%, and $8/day, alerting at 80%. That
is $6.40, under the $7.72 the stack costs a day, so the daily alert comes on
every full day the stack runs. AWS Budgets makes a forecast only once the
account has about five weeks of cost history, so in a new account the
forecast alert stays silent at first. The budgets send e-mail, and an e-mail
does not stop anything.
**Warning:** set a calendar reminder for the teardown date before you create
anything.

### Cheaper shapes

| | What changes | $/day | 15 days with GST |
|---|---|---|---|
| **A. As designed** | nothing | 7.72 | $137 |
| A′. Public subnets | `privateNetworking: false` and `vpc.nat.gateway: Disable` in `cluster.yaml`; no NAT gateway; nodes get public IPs | 6.42 | $114 |
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

### Cost safety

[`deploy/aws/lib.sh`](deploy/aws/lib.sh) pins the region to `ap-south-1` and
exports it, so no script depends on the caller's profile. A teardown run
against the wrong default region would report a clean sweep, because it would
be looking somewhere empty. [ADR 0010](adr/0010-region-ap-south-1.md) records
the choice of region. The files that set it are `cluster.yaml`,
`deploy/aws/lib.sh`, `k8s/base/configmap.yaml`,
`k8s/overlays/aws/kustomization.yaml`, `.github/workflows/build-and-deploy.yml`
and `src/main/resources/application.yml`. Cross-region drift shows up as an
IRSA or image-pull error, so set the CLI default to match:

```bash
aws configure set region ap-south-1
```

The three stacks `up.sh` deploys itself (the foundation, the data stack and
the Lambda) carry the tag `Project=flight-ops`. It passes
`--tags Project=flight-ops` to each one, and CloudFormation copies stack tags
to the resources that take them. `cluster.yaml` puts the same tag on what
eksctl creates from it: the cluster, its VPC and NAT gateway, and the node
group. A few things are left untagged: the four EKS addons, the two IAM roles
made by `eksctl create iamserviceaccount`, the load balancer controller's IAM
policy, and the shared SAM bucket. None of them bills more than cents. The
addons and the roles go with the cluster, and `down.sh` deletes the policy by
name. The catch-all query is:

```bash
aws resourcegroupstaggingapi get-resources --tag-filters Key=Project,Values=flight-ops
```

That query is the last check in `down.sh`. Some resource types take no tags, a
controller creates a few of them indirectly, and the Tagging API does not
cover every service. So each of the other checks lists one resource type, by
name or by tag. `down.sh` also fails if a query itself fails, because an
expired token returns the same empty string as a clean account.

The Kubernetes version is a cost control too. A version in extended support
bills $0.60 per cluster-hour, and standard support bills $0.10. Extended
support is on by default, so an aged-out version keeps running at six times
the price. `cluster.yaml` pins `1.36`, and `up.sh` sets the upgrade policy to
`STANDARD` right after creation. On 22 September 2026, standard support covered
1.36, 1.35 and 1.34, and extended support covered 1.33 and older. Re-check
with:

```bash
aws eks describe-cluster-versions \
  --query 'clusterVersions[?versionStatus==`STANDARD_SUPPORT`].[clusterVersion,endOfStandardSupportDate]' \
  --output table
```

The filter field is `versionStatus`. The older `status` field is deprecated in
the EKS API, and `clusterVersionStatus` does not exist and returns an empty
list.

No AWS account id is hard-coded anywhere. The files under `events/` use the
placeholder `123456789012`, and the workflow reads
`${{ secrets.AWS_ACCOUNT_ID }}`. No access key is stored either.
`DefaultCredentialsProvider` in `AwsConfig` reads `~/.aws` on a laptop and the
projected service-account token under IRSA. CI uses GitHub's OIDC provider and
short-lived STS credentials. `deploy/aws/foundation.yaml` pins the trust
policy's `sub` claim to this repository's `main` branch, because a wildcard
there lets any repository on GitHub assume the role.

## 6. Tearing it down

```bash
./deploy/aws/down.sh
```

Type `delete` when it asks. The deletes take about 20 minutes. Then the script
runs fourteen checks and exits non-zero if any of them finds something. They
cover both kinds of load balancer, clusters, instances, NAT gateways, volumes,
Elastic IPs, RDS instances and snapshots, stacks, log groups, secrets and ECR,
plus a catch-all query for anything tagged `Project=flight-ops`. The
catch-all runs in ap-south-1, and AWS reports IAM resources from us-east-1, so
it sees no IAM role or OIDC provider. IAM bills nothing, and the stacks check
still catches an eksctl stack that failed to delete, IRSA roles and all. With
`--keep-foundation` there are thirteen, because that flag leaves the ECR
repository behind and skips its check. It also leaves the foundation stack and
its own resources out of the stacks check and the tag catch-all. Any other
leftover still fails. The kept repository keeps its images, so CI's "Is this
commit already in ECR?" step reuses an image it pushed before.

The exit code answers "is it gone". The deletes themselves cannot, because a
CloudFormation stack can delete successfully and still leave a load balancer
behind. Each stack and the cluster print ✓ only after their wait confirms the
delete, and otherwise warn and leave the verdict to the checks.

`down.sh` writes a temporary kubeconfig for this cluster and never uses the
caller's current context, which may point at another cluster. If the cluster
cannot be reached, it warns that an ALB may be orphaned and asks you to type
`continue`. It then skips the load balancer controller and namespace steps.

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

4. **Lambda concurrency.** The SQS event source sets
   `ScalingConfig.MaximumConcurrency: 5`, so a backlog runs at most five
   invocations. It reserves nothing from the account pool and limits only the
   poller, and `template.yaml` explains the trade-off. Messages over the cap
   wait in the queue, and their receive count is not raised, so a long backlog
   is slow and does not reach the DLQ. An account pool that runs dry can still
   throttle, and that does raise the count towards `maxReceiveCount: 3`. Raise
   the cap before raising traffic; AWS accepts 2 to 1000.

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
