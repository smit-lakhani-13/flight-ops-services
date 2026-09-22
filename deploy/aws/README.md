# Deploying to AWS

Four scripts and two CloudFormation templates. Together they create the whole
demo — cluster, database, queue, Lambda, load balancer — from an account that
has nothing in it, and delete it again with proof that it is gone.

Nothing here has been run against a real account. Every template is linted and
every script is parsed and shellcheck-clean, but "it lints" is not "it worked",
and this file does not pretend otherwise. `DEPLOYMENT.md` records the date of
the first real run; until it does, that field reads `—`.

## Contents

- [The shape of it](#the-shape-of-it)
- [What it costs](#what-it-costs)
- [Before you start](#before-you-start)
- [Creating it](#creating-it)
- [Who creates what](#who-creates-what)
- [Checking the cost](#checking-the-cost)
- [Deleting it](#deleting-it)
- [When something goes wrong](#when-something-goes-wrong)
- [The files](#the-files)

## The shape of it

```
                         internet
                            │  http (no TLS — see DEPLOYMENT.md)
                     ┌──────▼──────┐
                     │     ALB     │  created by the LB controller
                     └──────┬──────┘  from k8s/components/ingress
   ┌────────────────────────▼─────────────────────────┐
   │  EKS, 2 x t3.medium, private subnets             │
   │    flight-ops  x2-4  (HPA on CPU)                │
   │       │ JDBC              │ SendMessage (IRSA)   │
   └───────┼───────────────────┼──────────────────────┘
           ▼                   ▼
      RDS Postgres        SQS booking-events ──► Lambda ──► DynamoDB
      db.t4g.micro             │                            bookings-projection
      private, no public IP    └─► DLQ after 3 attempts
```

The application half is the same code that runs on a laptop with
`./mvnw spring-boot:run`. What changes is where the database is, and that
`APP_EVENTS_PUBLISHER` is `sqs` rather than `noop`.

## What it costs

Mumbai (`ap-south-1`), on-demand, from the AWS price list:

| | per day |
|---|---|
| EKS control plane | $2.40 |
| 2 × t3.medium | $2.15 |
| NAT gateway (1.5 GB/day) | $1.43 |
| Application Load Balancer | $0.62 |
| RDS db.t4g.micro | $0.50 |
| EBS volumes, public IPv4 | $0.62 |
| Lambda, SQS, DynamoDB | free tier at this volume |
| **total** | **$7.72** |

| Running for | USD | with 18% GST | approx INR |
|---|---|---|---|
| 7 days | $54 | $64 | ₹6,100 |
| 10 days | $77 | $91 | ₹8,700 |
| 15 days | $116 | $137 | ₹13,100 |
| **30 days (forgotten)** | **$232** | **$273** | **₹26,200** |

The last row is the one that matters. `up.sh` creates two AWS Budgets that
e-mail at 50/80/100% of $60/month and at 80% of $12/day — but a budget alert is
an e-mail, not a brake. The only thing that stops the billing is `down.sh`.

Full cost model, alternatives (single EC2 at $0.80/day, Fargate, SAM-only) and
the "what breaks first if you cut it down" analysis are in
[../../DEPLOYMENT.md](../../DEPLOYMENT.md).

## Before you start

```bash
brew install awscli eksctl kubernetes-cli helm aws-sam-cli
aws configure                       # region ap-south-1
aws sts get-caller-identity         # must print your account id
```

`up.sh` also uses `openssl` and `htpasswd` (from `httpd`, present on macOS) to
generate the passwords, and `envsubst` (from `gettext`) if you render manifests
by hand.

The IAM user or role running this needs to create EKS clusters, VPCs, IAM roles
and RDS instances. In practice that means an administrator. A least-privilege
policy for this is a real piece of work and is not included; if that matters in
your account, run it as an administrator once and read the CloudTrail events.

One more thing, and it is not optional: **set a calendar reminder for the day
you intend to tear this down**, before you create anything.

## Creating it

```bash
ALERT_EMAIL=you@example.com ./deploy/aws/up.sh
```

About 50 minutes, nearly all of it waiting. Twelve numbered steps; step 1 prints
the cost table and asks you to type `yes`, and nothing before that point costs
anything. Every step checks whether its resource already exists first, so if it
fails halfway — a throttled API, a laptop that slept — run the same command
again rather than unpicking it by hand.

It stops once, after the infrastructure is up, and prints four values:

```
Secret    AWS_ACCOUNT_ID   123456789012
Variable  DEPLOY_ENABLED   true
Variable  SQS_QUEUE_URL    https://sqs.ap-south-1.amazonaws.com/…/booking-events
Variable  DB_URL           jdbc:postgresql://…:5432/flightops
```

Put those in the GitHub repository settings and run the workflow. The script
then waits for the rollout, creates the Ingress, waits for the load balancer,
and finishes by running [`demo.sh`](../../demo.sh) against the public URL — so
the last thing it prints is the whole system working end to end, over the
internet, including a booking that reaches DynamoDB through the queue.

### Why CI deploys the application and this script does not

The image tag is the commit SHA, and the only thing that knows the commit SHA
is the thing that built the image. A script that built and pushed from a laptop
would tag whatever happened to be checked out, including uncommitted changes,
and the cluster would then be running something that does not exist in git.

It also means no password ever reaches GitHub. `up.sh` generates the database
and API passwords and writes them straight into a Kubernetes Secret; CI applies
the Deployment, which *references* that Secret by name and never sees its
contents.

## Who creates what

| Resource | Created by | Why there |
|---|---|---|
| ECR, GitHub OIDC trust, CI role, budgets | `foundation.yaml` | must exist before CI can push anything |
| SQS, DLQ, DynamoDB, the Lambda | `template.yaml` via `sam deploy` | SAM owns its own stack; it is also the only half that is useful on its own |
| Cluster, VPC, NAT, nodes | `cluster.yaml` via `eksctl` | eksctl's VPC layout is what the RDS template reads its subnets from |
| RDS, its subnet group and security group | `data.yaml` | needs eksctl's VPC, so it cannot come earlier |
| IRSA roles, LB controller, metrics-server | `up.sh` | one-off cluster setup, not per-deploy |
| Namespace, Secret, EKS access entry | `up.sh` | holds passwords, and grants CI its scoped access |
| Deployment, Service, HPA, PDB, ConfigMap, ServiceAccount | CI, from `k8s/overlays/aws` | changes every release |
| Ingress, and therefore the ALB | `up.sh` | optional: without it the app is reachable by port-forward and costs $0.62/day less |

The split is deliberate: `up.sh` creates things that exist once, CI creates
things that change on every commit. The EKS access entry it grants CI is
`AmazonEKSEditPolicy` **scoped to the `flight-ops` namespace** — CI can roll out
the application and cannot touch `kube-system`, the load balancer controller, or
anything else on the cluster.

## Checking the cost

```bash
./deploy/aws/cost-check.sh        # last 7 days, by service, plus the forecast
./deploy/aws/cost-check.sh 14
```

Run it the morning after `up.sh` and then daily. Cost Explorer lags 8–24 hours,
so the current day is always incomplete — the forecast is the number to read,
not today's total.

## Deleting it

```bash
./deploy/aws/down.sh
```

Type `delete` to confirm. About 20 minutes. Then it runs a sweep — thirteen
checks for the things that bill, by name and by tag — and **exits non-zero if
any of them still exists**. That exit code is the answer to "is it definitely
gone?"; the deletes themselves are not, because a CloudFormation delete can
report success while leaving a load balancer behind.

Two orderings in that script are not cosmetic:

- **The Ingress is deleted first.** Delete the namespace with an Ingress still
  in it and the Kubernetes object disappears while the controller — being
  deleted at the same time — never gets the event that would delete the ALB. The
  load balancer survives, attached to nothing, at $0.62/day, indefinitely.
- **The database is deleted before the cluster.** Its security group lives in
  eksctl's VPC, and the VPC delete blocks on it. eksctl then fails after twenty
  minutes with a message about a dependency it declines to name.

`--keep-foundation` keeps ECR, the CI role and the budgets, so a later `up.sh`
skips the image rebuild. It costs about $0.10/month in ECR storage.

Two things the sweep cannot prove: Cost Explorer lags, so check again the next
day and expect zero rather than "small"; and data already transferred this month
is still billed at month end. Deleted is not refunded.

## When something goes wrong

| What you see | What it is |
|---|---|
| `kubectl get ingress` shows no ADDRESS, forever, no error | the load balancer controller is not running, or its IRSA role is missing. `kubectl logs -n kube-system deploy/aws-load-balancer-controller` |
| Pods `CrashLoopBackOff`, logs mention a password | the Secret is missing a key. `kubectl get secret flight-ops-secret -n flight-ops -o jsonpath='{.data}'` should list `DB_PASSWORD`, `API_PASSWORD`, `OPS_PASSWORD` |
| Pods start, then fail on the first request | `API_PASSWORD` has no `{bcrypt}` prefix. The service rejects an unprefixed value on purpose |
| `kubectl get hpa` shows `<unknown>/70%` | metrics-server is not installed. `aws eks describe-addon --cluster-name flight-ops-cluster --addon-name metrics-server` |
| Pods run but nothing reaches SQS | IRSA is not attached. `kubectl describe pod` should show `AWS_WEB_IDENTITY_TOKEN_FILE` |
| Connection timeouts to RDS | the security group admits the cluster SG and the shared node SG. Confirm with `aws ec2 describe-security-groups` that the ids in `data.yaml`'s parameters match the live cluster |
| `eksctl delete cluster` fails after 20 min | the data stack is still up. Delete it, then re-run `down.sh` |
| CI deploy fails with a 403 from EKS | the access entry is missing or the OIDC `sub` does not match `repo:owner/repo:ref:refs/heads/main` |

## The files

| File | What it is |
|---|---|
| `lib.sh` | shared helpers: pinned region, resource names, logging, typed confirmations, state file |
| `up.sh` | creates everything, in order, idempotently |
| `down.sh` | deletes everything, in reverse order, then proves it |
| `cost-check.sh` | daily cost by service, month-to-date, forecast, budget status |
| `render-aws.sh` | renders `k8s/overlays/aws` with the four environment values filled in; used by CI and by hand |
| `foundation.yaml` | ECR, GitHub OIDC provider and role, the SQS publish policy, two budgets |
| `data.yaml` | RDS PostgreSQL, its subnet group and security group |

`cluster.yaml` and `template.yaml` are at the repository root, where `eksctl`
and `sam` expect to find them.

The scripts keep their state in `deploy/aws/.state/flight-ops.env` — account id,
queue URL, database endpoint, load balancer hostname. It is gitignored, it holds
no passwords, and `down.sh` renames it to `.done` once the sweep passes clean.
