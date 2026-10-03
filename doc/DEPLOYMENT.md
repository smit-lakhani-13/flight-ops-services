# Deployment

There are three ways to run this service, costing $0, about $0 and $7.72 a
day. This document gives the commands for each one, prices the third, and shows
how to create it and, more importantly, how to destroy it.

**Executed on:** not yet.

It says not yet because none of this has been run against an AWS account.
The templates lint, the scripts pass shellcheck and a self-test
against stubbed tools, and the manifests validate against Kubernetes 1.36. None
of that proves they work. When it is run, this line gets the dates, and a new
last section, Evidence, gets the command output.

I wrote and reviewed the infrastructure on a machine with no container runtime
and no cluster. CI runs what it can reach: both Maven builds, every test (the
PostgreSQL integration tests included), and the image, which it builds, starts
without a database and scans. The rest needs a container registry, a cluster
or a funded AWS account, and the project has none of the three.

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
| **Localhost** | the API, the outbox, idempotency, seat locking, every test | JDK 21, optionally Docker; Node 24.15 or a later 24 and Playwright's Chromium for the console's tests | $0 |
| **Async half on AWS** | outbox → SQS → Lambda → DynamoDB, on real infrastructure | an AWS account, the SAM and AWS CLIs, JDK 21 | ~$0 (free tier) |
| **Full stack on EKS** | all of that plus rolling deploys, IRSA, HPA, a public URL | an AWS account, five CLIs, JDK 21, 50 minutes | $7.72/day |

The middle shape is the interesting half of the architecture:
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

If your JDK 21 is not at the path below, point `JAVA_HOME` at it instead
([CONTRIBUTING.md](../CONTRIBUTING.md#use-jdk-21) says why the build needs 21).

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./mvnw spring-boot:run
```

Then, in another terminal, from the same directory:

```bash
scripts/demo.sh
```

This runs on H2 in memory, and the outbox logs its rows instead of sending
them. Every [worked example](api.md#worked-examples) works, but nothing
survives a restart.

With Docker you also get what the H2 profile lacks: Flyway migrations, and
PostgreSQL's own row locks and `lock_timeout`.

```bash
docker compose up --build --wait
scripts/demo.sh
docker compose down -v
```

`--wait` starts the stack in the background and returns once `db` and `app`
report healthy, so the demo runs against it; `down -v` then removes the stack
and its database volume. `compose.yaml` is written for this and has not been
run end to end; the PostgreSQL tests in CI are the verified path.

[`compose.yaml`](../compose.yaml) sets `SPRING_PROFILES_ACTIVE=postgres`: the
local database, Flyway and the log publisher. The `Dockerfile` makes `prod` the
image's default with `ENV SPRING_PROFILES_ACTIVE=prod`, and
`deploy/k8s/base/configmap.yaml` selects `prod` as well. That profile expects
RDS, IRSA and the SQS publisher and has no default passwords. Run bare, with no
`DB_URL`, the image stops at startup with `'url' must start with "jdbc"`.
Without that default it would start on in-memory H2 and serve the `{noop}` dev
passwords. The `image` job checks for that failure on every push or pull
request to `main`, in the step "The image will not start without a database",
and the deploy job would push only an image that passed it.

`compose.yaml` publishes the application on `127.0.0.1:8080` only, so it
answers on the laptop and not to the rest of the network. A bare `8080:8080`
would listen on every interface, and on Linux, Docker's own firewall rules
bypass a host firewall such as ufw.

### PostgreSQL without compose

To run the app from source against a real database, start PostgreSQL in a
container and select the `postgres` profile:

```bash
docker run --name pg -e POSTGRES_PASSWORD=pass -e POSTGRES_DB=flightops \
  -p 127.0.0.1:5432:5432 -d postgres:17-alpine
DB_PASSWORD=pass ./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

The port is published on loopback only, as in `compose.yaml`. A bare
`-p 5432:5432` would offer a database whose password is `pass` to the rest of
the network.

`DB_PASSWORD` has no default. In the `postgres` profile,
`spring.datasource.password` is plain `${DB_PASSWORD}`, so `application.yml`
commits no working password, which a scanner would rightly flag in a public
repository. Leave the variable out and Hikari sends the literal string
`${DB_PASSWORD}`. Startup then fails at Flyway's first connection with
`FATAL: password authentication failed`, which names the wrong cause.
`docker compose up --build` needs none of this, because `compose.yaml` sets
`DB_PASSWORD`.

The `postgres` profile also changes who owns the schema. Flyway applies the
migrations in `src/main/resources/db/migration/`, from `V1__init.sql` to
`V11__flights_departure_time_index.sql`, and Hibernate runs
`ddl-auto: validate`. An entity that no longer matches the tables, columns or
column types then fails startup instead of altering them. Validation does not
compare check constraints or indexes.

## 3. The async half alone

```bash
export AWS_REGION=ap-south-1 AWS_DEFAULT_REGION=ap-south-1
rm -rf .aws-sam
./mvnw -B -q -f lambda/pom.xml clean package
sam deploy \
  --template-file lambda/template.yaml \
  --stack-name flight-ops-lambda \
  --resolve-s3 \
  --capabilities CAPABILITY_IAM \
  --no-confirm-changeset \
  --no-fail-on-empty-changeset \
  --tags Project=flight-ops
```

These are the commands step 3 of `up.sh` runs, from the repository root, in
the region `deploy/aws/lib.sh` pins, and they need JDK 21. Maven builds the
jar, and `sam build` is not used. SAM builds in a scratch copy of the
`CodeUri` directory, where the Lambda tests cannot find `../contracts`. So
`lambda/template.yaml` points `CodeUri` at the shaded jar,
`target/booking-event-handler.jar`, which SAM resolves against the template's
directory. `--template-file` names the template, so `sam deploy` never reads a
`.aws-sam/build` left by an earlier `sam build`. The second line changes nothing
for these commands; it only stops a later bare `sam deploy`, which does prefer
that built template, from deploying a stale copy.

This creates the queue, the DLQ, two CloudWatch alarms on them with no
notification target, the DynamoDB table and the Lambda. Then point a locally
running service at it:

```bash
SQS_QUEUE_URL=$(aws cloudformation describe-stacks --stack-name flight-ops-lambda \
  --query "Stacks[0].Outputs[?OutputKey=='QueueUrl'].OutputValue" --output text) \
APP_EVENTS_PUBLISHER=sqs \
./mvnw spring-boot:run
```

The service holds this terminal, so make a booking from another one, and
watch it arrive on the other side there. The count returns at once and the
tail runs until Ctrl-C, so the tail goes last:

```bash
aws dynamodb scan --region ap-south-1 --table-name flight-status-events \
  --select COUNT
sam logs -n BookingEventFunction --stack-name flight-ops-lambda \
  --region ap-south-1 --tail
```

The log line carries the `traceparent` of the HTTP request that made the
booking. With it, one request can be followed across a process boundary and a
queue.

Delete the stack with
`sam delete --stack-name flight-ops-lambda --region ap-south-1`, or with
[`deploy/aws/down.sh`](../deploy/aws/down.sh). Neither removes the
`aws-sam-cli-managed-default` bucket that `--resolve-s3` created, because every
SAM project in the account and region shares it. `down.sh --delete-sam-bucket`
removes it once you are sure nothing else uses it.

## 4. The full thing on AWS

The detailed runbook is [deploy/aws/README.md](../deploy/aws/README.md). The
short version:

```bash
brew install awscli eksctl kubernetes-cli helm aws-sam-cli openjdk@21
aws configure
aws sts get-caller-identity

ALERT_EMAIL=you@example.com ./deploy/aws/up.sh
```

Give `aws configure` the region `ap-south-1`, and check that
`aws sts get-caller-identity` prints your account id. `up.sh` takes about 50
minutes.

The preflight checks the tools, the credentials and `./mvnw -v`, which must
report JDK 21 because the enforcer rule in `lambda/pom.xml` accepts nothing
else. It also checks the sha256 of the load balancer controller's
[IAM policy file](#the-load-balancer-controllers-iam-policy), and asks AWS
whether the three versions the files pin are on offer in the region: the
Kubernetes version in `deploy/aws/cluster.yaml`, the RDS engine version in
`deploy/aws/data.yaml` and the controller's chart version in `deploy/aws/up.sh`.
Each would otherwise fail a later step with the cluster already billing
(`deploy/aws/lib.sh#require_eks_version`, `#require_rds_engine_version`,
`#require_helm_chart`). No system Maven
is needed. `gettext`, which provides `envsubst`, is needed only to run
`deploy/aws/render-aws.sh` by hand.

`up.sh` runs in twelve steps. Step 1 prints the cost table and asks you to type
`yes`, and nothing before that costs anything. Steps 2 to 8 build the
infrastructure, and the run then stops twice for you:

- Step 9 prints the generated API and ops passwords and waits for you to type
  `saved`. The closing summary repeats them. The cluster holds only their
  bcrypt hashes. A re-run that finds the Secret already there skips this.
- Step 10 prints the values to set in the GitHub repository settings, and waits
  for `done`. The CI role trusts only `main` of the repository that the
  `GitHubOwner` and `GitHubRepo` defaults in `deploy/aws/foundation.yaml` name,
  and `up.sh` does not override them, so in any other repository, set both to
  match it before the first run.

| | | |
|---|---|---|
| Secret | `AWS_ACCOUNT_ID` | your account id |
| Variable | `DEPLOY_ENABLED` | `true` |
| Variable | `SQS_QUEUE_URL` | from the SAM stack |
| Variable | `DB_URL` | from the data stack |

Before you set `DEPLOY_ENABLED`, merge the change that readies the first
deploy, and have it rename the deploy job in
`.github/workflows/build-and-deploy.yml` from `deploy (gated off)` to `deploy`.
Otherwise the first real deploy shows in the checks list under the gated-off
name.

Set those, run the workflow or push to `main`, then type `done`. The script
waits up to 30 minutes for CI to create `deployment/flight-ops`, then up to 20
for it to become available. The deploy job is skipped unless `DEPLOY_ENABLED`
is `true` and the run is on `main`, so a missing variable shows up as the
30-minute wait running out. The script then creates the Ingress, waits for the
load balancer, and finishes by running `scripts/demo.sh` against the public
URL. It skips the demo when the Secret came from an earlier run, because it
no longer knows the passwords.

CI deploys the application, and the script does not. The image tag is the
commit SHA of the CI run that built the image. A laptop build would tag
whatever happened to be checked out, including uncommitted work. The
split also keeps every password away from GitHub. `up.sh` writes the database
password and the bcrypt hashes of the API and ops passwords into a Kubernetes
Secret, and prints the API and ops passwords to the terminal. CI applies a
Deployment that refers to the Secret by name.

The deploy job builds and scans nothing. The `image` job builds the image,
starts it with no database and scans it, holding no AWS credentials. On a run
that can deploy, it saves the image and records the archive's sha256 before its
scan runs, and uploads it as a run artefact kept for one day once the scan
passes. The deploy job downloads the archive, checks that sha256, loads the
image, tags it with the commit SHA and pushes it. So CI scans the image once,
and the registry gets the bytes that were scanned. ECR would scan them again on
push (`ScanOnPush` in `deploy/aws/foundation.yaml#EcrRepository`), and that scan
would also report a HIGH, which the CI gate lets through because it fails only
on a CRITICAL. A "Re-run failed jobs" more than a day later finds no artefact
unless the image is already in ECR; re-run all jobs instead.

After the checkout, the deploy job's step "Is this commit still the head of
main?" compares `git ls-remote origin refs/heads/main` with the run's commit. A
re-run keeps the commit of the run it repeats, so "Re-run failed jobs" on an
older run would otherwise put that commit back over a newer release and go
green. When the two differ, the job assumes no role, pushes and applies nothing
and still passes, with a notice and a line in the job summary. The head of
`main` normally has a run of its own. When it has none, as after a commit that
skipped CI or a pending run that was cancelled, a manual run
(`workflow_dispatch`) on `main` deploys it, as it does for a deliberate
redeploy. The step compares the line whose ref is exactly `refs/heads/main`,
because `ls-remote` also lists any other ref whose name ends in that path.
Runs of commits from before the step have no such check: see
[Rolling back a deploy](OPERATIONS.md#rolling-back-a-deploy) for what that
means for a re-run.

Before it downloads the image, the deploy job asks ECR whether the commit's
image is already there, in the step "Is this commit already in ECR?". It runs
[`deploy/aws/ecr-image-exists.sh`](../deploy/aws/ecr-image-exists.sh), which
calls `ecr:DescribeImages` and reports the image missing only on
`ImageNotFoundException`. Any other error fails the job. Guessing "missing"
would push the image again and fail, because the repository's tags are
immutable.

After the rollout, the smoke test reaches one pod of the new release through a
port-forward. It reads the Deployment's revision, finds the ReplicaSet at that
revision and its `pod-template-hash`, and picks a Running, Ready pod with that
hash whose container runs this commit's image. It prints the pod and the image,
and fails if there is no such pod. `port-forward deployment/flight-ops`
would pick the pod that has been Ready longest, which right after a rollout is
the last old pod in its `preStop` sleep. The pod must answer readiness, the
root `/actuator/health` and `/v3/api-docs`.

When the smoke test passes, the job gives the image a second tag,
`deployed-<sha>`, with `ecr:BatchGetImage` and `ecr:PutImage`. The first rule
of the lifecycle policy in `deploy/aws/foundation.yaml#EcrRepository` keeps
the last ten images tagged that way, and the rule that keeps the last five
tagged images cannot expire them. Every deploy pushes its image before the
rollout, so without that tag five failed deploys in a row would expire the
image the old pods still run, and a pod on a new node or a
`kubectl rollout undo` could no longer pull it. A rollout that completes and
then fails the smoke test leaves the new pods serving an image with no such
tag; [Rolling back a deploy](OPERATIONS.md#rolling-back-a-deploy) covers that
case.

Every step of `up.sh` either checks whether its resource exists or uses a
command that is safe to repeat. To resume an interrupted run, run the same
command again. If eksctl stopped part
way through step 4, the re-run finishes the cluster. It waits for the control
plane, then creates whichever of the vpc-cni, kube-proxy and coredns addons, the
cluster's IAM OIDC provider and the `ng-1` node group is missing. Step 1 asks
for eksctl 0.184.0 or later, because older releases install those addons
self-managed and the re-run looks them up as EKS addons.

Two cases still need a hand. A re-run in the first minutes of step 4, before
EKS lists the cluster, calls `eksctl create cluster` a second time, and that
most likely fails on the existing CloudFormation stack. Wait for the stack to
settle, then re-run. The database password exists only in the shell from step 6
until step 9 writes the Secret. A run that stops in between stops again at
step 9, and the message says how to set a new password.

The Secret can also outlive its database: delete the data stack, keep the
cluster, and re-run. Step 6 then creates a new database with a new password,
and step 9 writes it into the existing Secret's `DB_PASSWORD` and restarts the
deployment, whose pods would otherwise keep the old value. The value reaches
`kubectl` on stdin, so it is on no command line and in no file. The API and
ops hashes stay, so the passwords from the first run still work. Step 6
records the new database in `deploy/aws/.state/flight-ops.env` before it
starts, and step 9 clears the record once the Secret holds the password and
the pods have restarted. A run that stops before the Secret is patched leaves
the record, so the next one stops at step 9 and says how to set a new
password, even with an old Secret there. If that next one's shell exports
`DB_PASSWORD`, as it does after those instructions, step 9 writes that value
in instead. Only a password step 6 generated in the same run is known to be
the new database's. An exported one may be left over from an earlier fix, so
step 9 warns that it cannot check it, that a wrong one fails every pod's
database login, and how to give the database the password the Secret then
holds. One that stops after the patch leaves the next one only the restart to
do. A re-run that finds no record leaves the Secret alone. Like the rest of
that file, the record belongs to the checkout the run started from.

The cluster's kubeconfig is the scripts' own. Step 4 points `KUBECONFIG` at
`deploy/aws/.state/kubeconfig`, mode 0600, before `eksctl create cluster`, so
neither eksctl nor `aws eks update-kubeconfig` writes `~/.kube/config` or
changes its current context. A context selected meanwhile in
`~/.kube/config`, or in any kubeconfig but the scripts' own, cannot send a
`kubectl` or `helm` call to another cluster. The closing summary prints the
`export KUBECONFIG=...` line for a shell of your own. A shell with that export
shares the file, and `aws eks update-kubeconfig --name <other>` there would
switch its current context under a running script.

### The service image

CI builds the image on an amd64 runner, and the t3.medium nodes that
`deploy/aws/cluster.yaml` defines are amd64 too, so the workflow passes no
`--platform` flag. A plain `docker build` on Apple Silicon produces an arm64
image, and a pod running it on those nodes would crash-loop with
`exec /bin/sh: exec format error`. Build locally with `--platform linux/amd64`
when the image is meant for the cluster. The Lambda runs on arm64, but it
ships as a jar and not an image, so this applies to the service image only.

The `image` job records the size on every run. In CI run 36328834211 on
2026-09-27, on `main` at 9f625d2, the image measured 300.3 MB (300252803 bytes),
as `docker image inspect` reports it on the runner. That image was built on the
pinned base images and holds the database CA bundle below. The image has never
been pushed, so no registry has reported a size for it. Both base images are
pinned by digest as well as tag, so the figure moves when a Dependabot pull
request moves the runtime base's digest or a dependency changes.

### The database connection

The data stack's `JdbcUrl` output in `deploy/aws/data.yaml` is the one place
the deployed JDBC URL is built. It ends in
`?sslmode=verify-full&sslrootcert=/app/certs/rds-global-bundle.pem`, so the
driver accepts only a server whose certificate chains to a CA in that file and
names the endpoint it dialled. Without `sslmode` the driver would use
`prefer`, which checks neither and falls back to plaintext when the server
declines TLS. `up.sh`, the aws overlay and the `prod` profile pass the URL on
unchanged. Localhost, the `postgres` profile, `compose.yaml` and CI have their
own URLs with no TLS, and none of them changes. Neither does the `image` job's
check that the image will not start without a database, because the image
still has no URL until `DB_URL` gives it one.

The file is the RDS global CA bundle, committed as
`certs/rds-global-bundle.pem`. The `Dockerfile` copies it into the runtime
stage, root-owned and read-only, at the path the URL names. It holds public
certificates and no key.

| | |
|---|---|
| Source | `https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem` |
| SHA-256 | `e5bb2084ccf45087bda1c9bffdea0eb15ee67f0b91646106e466714f9de3c7e3` |
| Certificates | 108 |
| Fetched | 2026-09-26 |

Refresh it when AWS publishes a new bundle, and before the instance moves to a
CA the committed file does not hold:

```bash
curl -sSf -o certs/rds-global-bundle.pem \
  https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem
shasum -a 256 certs/rds-global-bundle.pem
grep -c 'BEGIN CERTIFICATE' certs/rds-global-bundle.pem
```

Put the new checksum, count and date in the table and commit them with the
file. The `Dockerfile` copies the bundle at build time, so pods get the new
one only with the next image that is built and deployed.

The deploy job renders whatever the `DB_URL` repository variable holds. A
variable set from a data stack whose output had no `sslmode` keeps that old
URL until someone replaces it, in single quotes because of the `?` and `&`:

```bash
gh variable set DB_URL --body '<the JdbcUrl output>'
```

`up.sh` leaves an existing data stack alone, so a stack created from the older
template still outputs the old URL. Append the query string above to that
value by hand.

### The deploy job, gated off

Merging to `main` does not deploy anything. The
deploy job is gated on a `DEPLOY_ENABLED` repository variable that has never
been set. A gate on the branch alone would make the first push to a fresh
clone assume an IAM role built from an unset `AWS_ACCOUNT_ID` secret, and go
red for a reason unrelated to the code. So `build`, `infra-lint`, `trivy-fs`,
`docs-check`, `image` and `web` run on every push or pull request to `main`,
with `dependency-review` on pull requests only. The deploy job reports as
skipped until someone provisions the role with `up.sh` and sets the variable.
Read the green build badge as "it builds and the tests pass".

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
   because Kubernetes accepts a malformed edit to it and every role it maps can
   lose access at once, where an access entry is validated when it is created.
   The associate call's exit code
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
   deploy repeats it. The controller gets the region and VPC id from the Helm
   install, because the nodes do not let pods reach the instance metadata
   service. The controller's IAM policy comes from a file in this repository, as
   the next section describes.
9. **Namespace and Secret.** Generated passwords, never written to disk. The API
   and ops passwords are bcrypt-hashed. The database password is stored as it
   is, because the JDBC driver needs it. `htpasswd` reads each password on
   stdin, and `deploy/aws/lib.sh#create_secret` hands `kubectl create` the whole
   Secret on stdin, so no value is on a command line, where the process list
   would show it. An existing Secret is left alone, unless step 6 created a
   database whose password the Secret does not hold yet, in this run or in one
   that stopped before step 9: then only `DB_PASSWORD` changes, and the
   deployment is restarted. A `DB_PASSWORD` that step 6 of this run did not
   generate goes into a new Secret or an existing one after the same warning,
   `deploy/aws/lib.sh#warn_db_password_from_shell`.

### The load balancer controller's IAM policy

The controller's IRSA role gets the IAM policy that the controller's
maintainers publish with each release, as the install guide's iam_policy.json
in the kubernetes-sigs/aws-load-balancer-controller repository. `up.sh` does
not download it. A tag is a mutable pointer in someone else's repository, so a
download would put whatever the tag pointed at that day on the role. The copy
for the release `up.sh` installs is committed as
`deploy/aws/lbc-iam-policy-v3.5.0.json`, byte for byte, upstream's
indentation included, so a diff against the next release shows only what
changed:

- Source, fetched on 26 September 2026:
  [iam_policy.json at v3.5.0](https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/v3.5.0/docs/install/iam_policy.json)
- Tag: `v3.5.0`, in `deploy/aws/up.sh#LBC_POLICY_TAG`, matching the Helm chart
  version in `deploy/aws/up.sh#LBC_CHART_VERSION`
- sha256: `16f232c9d9f79366fe949c4550ad517a202380058a9e48d45a4e215044a20a6a`,
  in `deploy/aws/up.sh#LBC_POLICY_SHA256`

Step 1 checks the file against the sum with
`deploy/aws/lib.sh#require_sha256`, before anything bills, and step 8 checks
it again just before it creates the policy from it. `deploy/aws/selftest.sh`
makes the same check in CI. A file changed without its sum stops all three,
so a change to what the controller may do arrives as a reviewed diff.

The policy is named `flight-ops-lbc-v3.5.0`, from
`deploy/aws/lib.sh#LBC_POLICY_PREFIX` and the tag, and tagged
`Project=flight-ops`. AWS's install guide calls the same policy
`AWSLoadBalancerControllerIAMPolicy`, so an account with another cluster may
already hold one by that name, perhaps from an older release. `up.sh` never
attaches it, and `down.sh` never deletes it. `down.sh` deletes every customer
managed policy whose name starts with `flight-ops-lbc-`, after the cluster
delete has removed the role it was attached to.

To move to a new release, change the tag, the file, the sum and the documents
that name them in one commit:

1. Download the new release's file beside the old one, and read the diff.
   Every added action is a new permission for the controller. Set `new` to the
   new release's tag:

   ```bash
   old=$(sed -n 's/^LBC_POLICY_TAG=\([^ ]*\).*/\1/p' deploy/aws/up.sh)
   new=v3.6.0
   repo=kubernetes-sigs/aws-load-balancer-controller
   curl -fsSL -o "deploy/aws/lbc-iam-policy-$new.json" \
     "https://raw.githubusercontent.com/$repo/$new/docs/install/iam_policy.json"
   diff -u "deploy/aws/lbc-iam-policy-$old.json" \
     "deploy/aws/lbc-iam-policy-$new.json"
   shasum -a 256 "deploy/aws/lbc-iam-policy-$new.json"
   ```

   Without `shasum`, `sha256sum` prints the same sum.

2. Delete the old file with `git rm`. In `deploy/aws/up.sh`, set
   `LBC_POLICY_TAG` to the new tag, `LBC_CHART_VERSION` to the chart that
   installs that release, and `LBC_POLICY_SHA256` to the sum just printed.
   The policy's name follows the tag.
3. Update every document that names the release. In this section, that is
   the committed file's path, the source link, the fetch date, the tag, the
   sum and the policy's name.
   `SECURITY.md`, `deploy/aws/README.md` (the "Who creates what" row, the
   checksum row under "When something goes wrong" and the file table) and the
   file tree in `doc/ARCHITECTURE.md` name the file, the policy or both.
   `git grep -n -F "$old"` lists what is left. Leave the changelog's released
   sections as they are, and add an Unreleased entry instead. The fixture
   ARNs in `deploy/aws/selftest.sh` need no change.
4. Run `deploy/aws/selftest.sh`, which fails while the file and the sum
   disagree, and `python3 scripts/refcheck.py`, which fails while a path in a
   code span still names the old file. It does not read the file tree in
   `doc/ARCHITECTURE.md` or the README's checksum row, so the `git grep`
   in step 3 is what finds those. Both must pass.

Make the move while no cluster exists. On a running cluster, a re-run of
`up.sh` creates the new policy but leaves the controller's role on the old
one, because eksctl does not change a service account it has already
created.

### The manifests

| Path | What it holds |
|---|---|
| `deploy/k8s/base/` | `configmap`, `serviceaccount`, `deployment`, `service`, `hpa` and `pdb`: everything true in any environment |
| `deploy/k8s/overlays/aws/` | the ECR image, the IRSA role annotation, the queue URL and the database URL |
| `deploy/k8s/components/ingress/` | the Ingress, and so the ALB. It is separate because applying it starts a continuous charge |
| `deploy/k8s/namespace.yaml` | cluster-scoped, so CI's namespace-scoped role cannot apply it. `up.sh` does |
| `deploy/k8s/secret.example.yaml` | a template. `up.sh` generates the real Secret and never writes it to disk |

CI renders the overlay with
`AWS_ACCOUNT_ID=… IMAGE_TAG=… SQS_QUEUE_URL=… DB_URL='…' ./deploy/aws/render-aws.sh`,
and a person can run the same command. It exits 2 if any of the four is unset
or empty, and 3 if a `${…}` placeholder survives substitution. So a missing
value is a failed command, and never a manifest holding the literal
`${DB_URL}`.

To read the manifests before any value is filled in, run
`kubectl kustomize deploy/k8s/overlays/aws`. It prints the overlay with the
`${…}` placeholders still in it, which is what `render-aws.sh` passes to
`envsubst`.

The pod has three probes. The `startupProbe` allows up to 30 × 10s for the JVM,
Spring and Flyway to start, so the liveness timeout can stay tight. The
`readinessProbe` on `/actuator/health/readiness` gates traffic. The
`livenessProbe` on `/actuator/health/liveness` restarts a wedged container.

The rolling update sets `maxUnavailable: 0`, and a `PodDisruptionBudget` covers
voluntary disruptions such as a node drain. A rollout and a drain are different
events, so each has its own guard.

Both count a pod as available once it is Ready, and behind the ALB Ready has to
include the load balancer's view. `deploy/k8s/namespace.yaml` labels the
namespace `elbv2.k8s.aws/pod-readiness-gate-inject: enabled`, so while the
Ingress exists the load balancer controller adds a readiness gate to each new
pod. The pod is then Ready only once the ALB reports its target healthy, and
neither a rollout nor a drain can remove the last healthy target while a new
one is still in its first health checks. The gate is added when a pod is
created. Pods already running when the label or the Ingress arrives have none
until the next rollout replaces them, and that rollout already waits on the
ALB, because its new pods carry the gate. CI's role cannot label the
namespace, so a namespace created before the label gets it only when `up.sh`
runs again or someone applies `namespace.yaml` by hand. The cost is that a pod
in this namespace is created only while the controller's webhook answers. With
the controller down, a rollout waits with `FailedCreate` events and the old
pods keep serving.

The heap is `-XX:MaxRAMPercentage=50.0`, so it follows the container's 768Mi
memory limit, and going over that limit gets the container OOMKilled. There is
no CPU limit. CFS throttling hits a JVM hardest during class loading and GC,
inside the startup probe's window. It would also make the HPA measure the
throttle instead of the load.

Shutdown is a 15s `preStop` sleep plus a 30s
`spring.lifecycle.timeout-per-shutdown-phase`. That is 45s, inside the 55s
`terminationGracePeriodSeconds`. The sleep keeps the pod serving while the
load balancer controller deregisters its target and the ALB stops sending to
it, which can take longer than a few seconds. Get that inequality backwards
and the kubelet sends SIGKILL mid-request. I pinned the 30s in
`application.yml`, because the manifest comment does arithmetic on it, and
the build fails if the sum stops fitting
(`ShutdownBudgetTest.java#preStopAndDrainFitInTheGracePeriod`).

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
waits on `SELECT … FOR UPDATE`, which may not show as CPU. The better signal
is request rate or queue depth, through KEDA or the Prometheus adapter.
`/actuator/prometheus` already serves, because `micrometer-registry-prometheus`
is on the classpath, so the adapter is the missing piece.

To anyone with `get secret` in the namespace, a Kubernetes Secret is only
base64-encoded, as `secret.example.yaml` says. The production answer is Secrets
Manager through the Secrets Store CSI driver.

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
| ECR, up to about 1.5 GB of images (15 kept at most) | $0.10/GB-month after any free tier | $0.00 |
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
until after the weekend. Creating the stack should take about 50 minutes and
deleting it about 20, and neither number decides the bill. Whether someone
remembers the teardown does, which is why the 30-day row is here.

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
| A′. Public subnets | `privateNetworking: false` and `vpc.nat.gateway: Disable` in `deploy/aws/cluster.yaml`; no NAT gateway; nodes get public IPs | 6.42 | $114 |
| B. EKS Fargate | no node group; CoreDNS and the controller also move to Fargate; more setup | 7.12 | $126 |
| **C. One EC2 t3.small** | `compose.yaml` on a single instance, with the override described below, plus the SAM stack. No EKS, no ALB, no RDS | **0.80** | **$14** |
| D. Prepare only | nothing created | 0 | $0 |

If the goal is a live URL, C is the one to take seriously. It is a t3.small at
$0.0224/hour running `docker compose up` with an Elastic IP, plus the free-tier
SAM stack. With the changes the next paragraph lists, and an instance role that
may send to the SAM stack's queue, it would show the service running on AWS,
and nothing about EKS.
Ninety per cent of the cost of shape A is what EKS, its networking and RDS add
over shape C. Whether that is worth $123 over fifteen days depends on who is
asking.

`compose.yaml` is not ready for shape C as it stands. It publishes port 8080 on
loopback only, and its two password hashes are of passwords the README prints.
Shape C needs a compose override file that sets its own bcrypt hashes for
`API_PASSWORD` and `OPS_PASSWORD` and puts a proxy that terminates TLS in front
of the application, in place of a bare port 8080 open to the internet. For the
SAM stack to receive events, it also needs `APP_EVENTS_PUBLISHER=sqs` and
`SQS_QUEUE_URL`, because `compose.yaml` selects the log publisher.

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
row in the table before. The control plane's $73 accrues even with no worker
nodes, from creation until the cluster is deleted, so a cluster scaled to zero
nodes still costs about $0.10 an hour.

Some resources outlive a botched teardown without any error, because nothing
points at them any more. An orphaned ALB costs $19/month, an unassociated
Elastic IP $3.60/month each, and an unattached EBS volume $1.80/month per 20 GB.
The final sweep in [`down.sh`](../deploy/aws/down.sh) checks for all three: the
load balancer by name, and the Elastic IPs and volumes by the
`Project=flight-ops` tag.

### Watching it

```bash
./deploy/aws/cost-check.sh
```

It prints the daily cost by service, the month to date and the forecast.

Cost Explorer lags by 8 to 24 hours, so today's figure is always incomplete.
Read the forecast instead of today's total.

### Cost safety

[`deploy/aws/lib.sh`](../deploy/aws/lib.sh) pins the region to `ap-south-1` and
exports it, so no script depends on the caller's profile. A teardown run
against the wrong default region would report a clean sweep, because it would
be looking somewhere empty. [ADR 0010](../adr/0010-region-ap-south-1.md) records
the choice of region. The files that set it are `deploy/aws/cluster.yaml`,
`deploy/aws/lib.sh`, `deploy/k8s/base/configmap.yaml`,
`deploy/k8s/overlays/aws/kustomization.yaml`,
`.github/workflows/build-and-deploy.yml` and
`src/main/resources/application.yml`. Cross-region drift shows up as an IRSA or
image-pull error, so set the CLI default to match:

```bash
aws configure set region ap-south-1
```

The three stacks `up.sh` deploys itself (the foundation, the data stack and the
Lambda) carry the tag `Project=flight-ops`. It passes
`--tags Project=flight-ops` to each one, and CloudFormation copies stack tags
to the resources that take them. `deploy/aws/cluster.yaml` puts the same tag
on what eksctl creates from it: the cluster, its VPC and NAT gateway, and the
node group. `up.sh` creates the load balancer controller's IAM policy outside
any stack, so it tags that policy itself, and `down.sh` deletes it by its
`flight-ops-lbc-` prefix and warns if it cannot. The tag shows the policy in the
console; the catch-all query below does not list it, for the reason section 6
gives. A few things are left untagged: the four EKS addons, the two IAM roles
made by `eksctl create iamserviceaccount`, and the shared SAM bucket. None of
them bills more than cents, and the addons and the roles go with the cluster.
The catch-all query is:

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
the price. `deploy/aws/cluster.yaml` pins `1.36`, and `up.sh` sets the upgrade
policy to `STANDARD` right after creation. On 22 September 2026, standard
support covered 1.36, 1.35 and 1.34, and extended support covered 1.33 and
older. Re-check with:

```bash
aws eks describe-cluster-versions \
  --query 'clusterVersions[?versionStatus==`STANDARD_SUPPORT`].[clusterVersion,endOfStandardSupportDate]' \
  --output table
```

The filter field is `versionStatus`. The older `status` field is deprecated in
the EKS API, and `clusterVersionStatus` does not exist and returns an empty
list.

No AWS account id is hard-coded anywhere. The files under `lambda/events/` use
the placeholder `123456789012`, and the workflow reads
`${{ secrets.AWS_ACCOUNT_ID }}`. No access key is stored either.
`DefaultCredentialsProvider` in `AwsConfig` reads `~/.aws` on a laptop and the
projected service-account token under IRSA. That token is the pod's only AWS
or Kubernetes credential: `automountServiceAccountToken` is false, so no
Kubernetes API token is mounted, and `disablePodIMDS` in
`deploy/aws/cluster.yaml` stops pods reaching the instance metadata service
and the node role behind it. That setting applies when a node group is
created, so a node group from before it keeps the old behaviour until it is
replaced. The deploy job, which is gated off and has never run, would use
GitHub's OIDC provider and short-lived STS credentials.
`deploy/aws/foundation.yaml` pins the trust policy's `sub` claim to this
repository's `main` branch, because a bare wildcard there lets any repository
on GitHub assume the role.

## 6. Tearing it down

```bash
./deploy/aws/down.sh
```

Type `delete` when it asks. The deletes take about 20 minutes. Then the script
runs fourteen checks and exits non-zero if any of them finds something. They
cover both kinds of load balancer, clusters, instances, NAT gateways, volumes,
Elastic IPs, RDS instances and snapshots, stacks, log groups, secrets and ECR,
plus a catch-all query for anything tagged `Project=flight-ops`. The catch-all
runs in ap-south-1, and AWS reports IAM resources from us-east-1, so it sees no
IAM role, IAM policy or OIDC provider. IAM bills nothing, and the stacks check
still catches an eksctl stack that failed to delete, IRSA roles and all. With
`--keep-foundation` there are thirteen, because that flag leaves the ECR
repository behind and skips its check. It also leaves the foundation stack and
its own resources out of the stacks check and the tag catch-all. Any other
leftover still fails. The kept repository keeps the images its lifecycle policy
allows, so a later deploy of a commit whose image is still there skips the
download, load and push that follow CI's "Is this commit already in ECR?" step.

The exit code answers "is it gone". The deletes themselves cannot, because a
CloudFormation stack can delete successfully and still leave a load balancer
behind. Each stack and the cluster print ✓ only after their wait confirms the
delete, and otherwise warn and leave the verdict to the checks.

`down.sh` uses the scripts' own kubeconfig, `deploy/aws/.state/kubeconfig`,
rewrites this cluster's entry in it, and never uses the caller's current
context, which may point at another cluster. If the cluster cannot be reached,
it warns that an ALB may be orphaned and asks you to type `continue`. It then
skips the load balancer controller and namespace steps. When the checks pass,
it deletes the kubeconfig.

The order matters:

- **Delete the Ingress first.** The controller runs in `kube-system`, not in
  the application namespace. Uninstall it or delete the cluster while the
  Ingress still exists, and nothing is left to delete the ALB. The load
  balancer stays up, attached to nothing, at $19/month, until someone finds it
  in the EC2 console.

- **Database before the cluster.** Its security group is in eksctl's
  VPC, and the VPC delete blocks on it. eksctl then fails after twenty minutes
  with a message about a dependency it does not name.

If a check fails, run the script again. A second pass should clear a delete
that had not finished, or one that was blocked by another still in progress.

The checks say nothing about the bill. Cost Explorer lags, so look again the
next day and expect zero, not "small". Data transferred earlier in the month is
still billed at month end, because deleting a resource refunds nothing.

`down.sh` changes nothing in GitHub, and prints what is left to do there. Set
`DEPLOY_ENABLED` back to `false` before you start, or every push to `main`
runs the deploy job against a cluster that no longer exists, and fails. After
the teardown, rename the job back to `deploy (gated off)`, with
`CONTRIBUTING.md` in the same commit, so that
[the gate](#the-deploy-job-gated-off) shows in the checks list again.

## 7. What breaks first

Reasoned from the configuration, not measured under load, the order would be:

1. **Database connections.** Four pods × a Hikari pool of 10 = 40 connections,
   against the fewer than 112 that db.t4g.micro allows (read it with
   `SHOW max_connections`). So `deploy/k8s/base/hpa.yaml` caps `maxReplicas`
   at 4, well below what the cluster could hold. Raise the instance class
   before the replica count. Otherwise the symptom is "remaining connection
   slots are reserved", which looks like a database fault but comes from the
   replica count.

2. **Seat lock contention.** Concurrent bookings for the *same flight* queue
   behind `SELECT ... FOR UPDATE`. That queue is what prevents overselling, so
   it is expected. `SET lock_timeout = '3s'` bounds the wait, and after that a
   request gets 503 with `Retry-After`. Different flights never contend for the
   lock, so this limit depends on concurrency per flight and not on total
   traffic. They do share each pod's pool, though: a hot flight's waiters each
   hold a connection, so other requests on that pod can wait for one, 5 s at
   most before `503 DATABASE_UNAVAILABLE`.

3. **Outbox drain rate.** Each replica's publisher claims up to 100 rows with
   `FOR UPDATE SKIP LOCKED` and sends them one at a time, each a blocking
   `SendMessage`
   (`src/main/java/com/smit/flightops/service/SqsEventPublisher.java#publish`).
   The job is `fixedDelay`, so the next drain starts a second after the last
   one ends. With a send latency of L seconds, a replica drains about
   `100 / (1 + 100 × L)` events a second. 100 a second is a bound it never
   reaches, and `1 / L` is its ceiling whatever the batch size or interval.
   Nothing here has measured L. If it were 20 ms, a drain would spend 2 s
   sending, for 100 / 3, about 33 events a second. A batch of 1000 would give
   1000 / 21, about 48, and hold its row locks and a pooled connection for
   20 s each drain. A 100 ms interval would give 100 / 2.1, also about 48,
   with no longer hold. The SQS client bounds each send at 5 s, retries
   included
   (`src/main/java/com/smit/flightops/config/AwsConfig.java#sqsClient`), so a
   drain of 100 whose sends all time out takes up to 500 s. More replicas
   raise the total, each claiming a disjoint batch, up to the four of point 1,
   and the CPU-based HPA does not add them for a backlog alone. Past that,
   short of the larger instance class point 1 describes, the change is in
   code: `SendMessageBatch`, in the SQS SDK the service already uses, takes
   up to ten messages a call, and the drain would have to map each entry's
   failure back to its row. The `outbox.pending` gauge shows the backlog
   before anyone notices it downstream.
   [OPERATIONS.md](OPERATIONS.md#events-stop-arriving-outbox_pending-climbs)
   has the playbook, and how to measure L.

4. **Lambda concurrency.** The SQS event source sets
   `ScalingConfig.MaximumConcurrency: 5`, so a backlog runs at most five
   invocations. It reserves nothing from the account pool and limits only the
   poller, and `lambda/template.yaml` explains the trade-off. Messages over the
   cap wait in the queue, and their receive count is not raised, so a long
   backlog is slow and does not reach the DLQ. A message still waiting 10 days
   after it was sent is deleted. An account pool that runs dry can still
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
3. Add these annotations to `deploy/k8s/components/ingress/ingress.yaml`:
   ```yaml
   alb.ingress.kubernetes.io/listen-ports: '[{"HTTP":80},{"HTTPS":443}]'
   alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:ap-south-1:…:certificate/…
   alb.ingress.kubernetes.io/ssl-redirect: '443'
   ```
4. Point the domain at the ALB: an ALIAS record in Route 53, or a CNAME
   elsewhere.
5. Optionally, pin `server.forward-headers-strategy: native`. In a pod,
   Spring Boot already detects Kubernetes and adds Tomcat's `RemoteIpValve`,
   which trusts `X-Forwarded-Proto` from private addresses, the ALB's
   included. So redirects and generated links stay on `https` without this
   step, and pinning `native` keeps that if the platform is not detected.
   Avoid `framework`: it swaps the valve for Spring's `ForwardedHeaderFilter`,
   which does not check where the headers came from.

ACM certificates are free, and the ALB costs the same either way. The change
takes about ten minutes plus DNS propagation. The only thing stopping me is the
domain.
