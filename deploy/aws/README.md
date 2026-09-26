# Deploying to AWS

Six scripts, a shared library, two CloudFormation templates and the eksctl
cluster definition. Together they create the whole demo (cluster, database,
queue, Lambda, load balancer) in an empty account, and delete it again with
proof that it is gone.

Nothing here has been run against a real account. The templates lint, the
scripts parse and are shellcheck-clean, and `selftest.sh` runs the teardown,
the cost check, the ECR lookup and `up.sh`'s checks against stubbed tools.
None of that is a real run.
`doc/DEPLOYMENT.md` records the date of the first one; until then that field
reads `—`.

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
                            │  http (no TLS, see doc/DEPLOYMENT.md)
                     ┌──────▼──────┐
                     │     ALB     │  created by the LB controller
                     └──────┬──────┘  from deploy/k8s/components/ingress
   ┌────────────────────────▼─────────────────────────┐
   │  EKS, 2 x t3.medium, private subnets             │
   │    flight-ops  x2-4  (HPA on CPU)                │
   │       │ JDBC              │ SendMessage (IRSA)   │
   └───────┼───────────────────┼──────────────────────┘
           ▼                   ▼
      RDS Postgres        SQS booking-events ──► Lambda ──► DynamoDB
      db.t4g.micro             │                          flight-status-events
      private, no public IP    └─► DLQ after 3 attempts
```

The application half is the same code that runs on a laptop with
`./mvnw spring-boot:run`. What changes is where the database is, and that
`APP_EVENTS_PUBLISHER` is `sqs` instead of `log`.

## What it costs

It bills by the hour from the moment the cluster exists (step 4) until
`down.sh` deletes it. The daily rate, the
totals for a week or a forgotten month, and the cheaper shapes are in
[DEPLOYMENT.md section 5](../../doc/DEPLOYMENT.md#5-what-it-costs); `up.sh`
prints the same table at step 1 before it asks to start.

`up.sh` creates two AWS Budgets that send e-mail at set shares of a monthly and
a daily limit. A budget alert is an e-mail and stops nothing. Only `down.sh`
stops the billing.

## Before you start

```bash
brew install awscli eksctl kubernetes-cli helm aws-sam-cli openjdk@21
aws configure                       # region ap-south-1
aws sts get-caller-identity         # must print your account id
```

`up.sh` builds the Lambda jar with the Maven wrapper, which needs JDK 21 on
`JAVA_HOME`; step 1 checks this before anything bills. It also uses `openssl`
and `htpasswd` (from `httpd`, present on macOS) to generate the passwords.
`envsubst` (from `gettext`) is needed only to run `render-aws.sh` by hand.

The IAM user or role running this needs to create EKS clusters, VPCs, IAM roles
and RDS instances, which in practice means an administrator. No least-privilege
policy is included. If that matters in your account, run it as an
administrator once and read the CloudTrail events.

Before you create anything, set a calendar reminder for the day you intend to
tear it down.

## Creating it

```bash
ALERT_EMAIL=you@example.com ./deploy/aws/up.sh
```

About 50 minutes, nearly all of it waiting. Twelve numbered steps; step 1 prints
the cost table and asks you to type `yes`, and nothing before that costs
anything. Every step checks whether its resource already exists, so if a run
fails halfway (a throttled API, a laptop that slept), run the same command
again instead of unpicking it by hand. If eksctl stopped part way through
step 4 after EKS listed the cluster, the re-run finishes it, creating whichever
of its networking addons, OIDC provider and node group is missing. A stop in
the first minutes, before EKS lists the cluster, needs its CloudFormation stack
to settle before the re-run (`doc/DEPLOYMENT.md` section 4). A run that stops
between steps 6 and 9 is different: the database password was only in that
shell, and step 9 says how to set a new one.

After step 1 it stops twice more. On a first run, step 9 prints the generated
passwords and waits for `saved`. Step 10 prints four values and waits for
`done`:

```
Secret    AWS_ACCOUNT_ID   123456789012
Variable  DEPLOY_ENABLED   true
Variable  SQS_QUEUE_URL    https://sqs.ap-south-1.amazonaws.com/…/booking-events
Variable  DB_URL           jdbc:postgresql://…:5432/flightops?sslmode=verify-full&sslrootcert=/app/certs/rds-global-bundle.pem
```

Before you set `DEPLOY_ENABLED`, rename the deploy job in
`.github/workflows/build-and-deploy.yml` from `deploy (gated off)` to `deploy`,
in the change that readies the first deploy. Otherwise the first real deploy
shows in the checks list under the gated-off name.

Put those in the GitHub repository settings and run the workflow. The script
waits up to 30 minutes for CI to create the deployment, and up to 20 for the
rollout. Then it creates the Ingress, waits for the load balancer, and finishes
by running [`scripts/demo.sh`](../../scripts/demo.sh) against the public URL.
The demo is eight acts over HTTP and never looks at the queue or the table. To
see bookings arrive in DynamoDB through the queue:

```bash
aws dynamodb scan --table-name flight-status-events --select COUNT
```

### Why CI deploys the application and this script does not

The image tag is the commit SHA, and only the job that built the image knows
it. A script that built and pushed from a laptop would tag whatever was checked
out, including uncommitted changes, and the cluster would run something that
does not exist in git.

No password reaches GitHub either. `up.sh` generates the database, API and ops
passwords. It writes the database password and the bcrypt hashes of the other
two into a Kubernetes Secret, and prints the API and ops passwords to the
terminal. CI applies the Deployment, which names that Secret and never sees
its contents.

## Who creates what

| Resource | Created by | Why there |
|---|---|---|
| ECR, GitHub OIDC trust, CI role, budgets | `foundation.yaml` | must exist before CI can push anything |
| SQS, DLQ, two CloudWatch alarms, DynamoDB, the Lambda | `lambda/template.yaml` via `sam deploy`, from the jar Maven builds | SAM owns its own stack; it is also the only half that is useful on its own |
| Cluster, VPC, NAT, nodes | `cluster.yaml` via `eksctl` | eksctl's VPC layout is what the RDS template reads its subnets from |
| RDS, its subnet group and security group | `data.yaml` | needs eksctl's VPC, so it cannot come earlier |
| IRSA roles, LB controller, metrics-server | `up.sh` | one-off cluster setup, not per-deploy |
| Namespace, Secret, EKS access entry | `up.sh` | holds passwords, and grants CI its scoped access |
| Deployment, Service, HPA, PDB, ConfigMap, ServiceAccount | CI, from `deploy/k8s/overlays/aws` | changes every release |
| Ingress, and therefore the ALB | `up.sh` | optional: without it the app is reachable by port-forward, and the ALB's share of the bill goes |

`up.sh` creates what exists once; CI creates what changes on every commit. The
EKS access entry `up.sh` grants CI is `AmazonEKSEditPolicy` scoped to the
`flight-ops` namespace, and step 5 checks that scope after associating it. CI
can roll out the application and cannot touch `kube-system`, the load balancer
controller or anything else on the cluster.

## Checking the cost

```bash
./deploy/aws/cost-check.sh        # last 7 days, by service, plus the forecast
./deploy/aws/cost-check.sh 14
```

Run it the morning after `up.sh` and then daily. Cost Explorer lags 8 to 24
hours, so the current day is always incomplete; read the forecast, not
today's total. If Cost Explorer is not enabled on the account, the script says
so and still prints the budget status.

## Deleting it

```bash
./deploy/aws/down.sh
```

Type `delete` to confirm. About 20 minutes. Then it runs a sweep of fourteen
checks for the things that bill, by name and by tag, and exits non-zero if any
of them still exists. A check that cannot reach AWS fails: an empty result and
a failed call look the same on stdout, so every query turns a non-zero exit
into output the check reports. That exit code answers "is it gone?". A delete
step's own message does not, because a CloudFormation delete can report
success while leaving a load balancer behind; each stack and the cluster print
`✓` only when their wait confirms the delete.

`down.sh` writes its own temporary kubeconfig for the cluster and checks that
the API server answers. If it cannot reach the cluster, it says so, asks you to
type `continue`, and skips the Helm and namespace steps. It never acts on the
context your shell had selected.

Two orderings in that script matter:

- **Ingress first.** Delete the namespace with an Ingress still in it, and the
  controller, deleted at the same moment, never receives the event that would
  delete the ALB. The load balancer survives, attached to nothing, and bills
  until someone finds it.

- **Database before the cluster.** Its security group lives in
  eksctl's VPC, and the VPC delete blocks on it. eksctl then fails after twenty
  minutes with a message about a dependency it declines to name.

`--keep-foundation` keeps ECR, the CI role and the budgets. CI's "Is this
commit already in ECR?" step then finds an image already pushed for the commit
it deploys, and skips the build. The sweep leaves out the foundation stack and
the resources it owns, and still checks everything else. The kept images stay
in ECR, which bills storage at $0.10 per GB-month after any free tier, so five
images cost cents a month. The shared SAM bucket also stays, as it does after a
full teardown, unless you pass `--delete-sam-bucket`.

Two things the sweep cannot prove. Cost Explorer lags, so check again the next
day and expect zero, not "small". And data already transferred this month is
still billed at month end.

## When something goes wrong

| What you see | What it is |
|---|---|
| `kubectl get ingress` shows no ADDRESS, forever, no error | the load balancer controller is not running, or its IRSA role is missing. `kubectl logs -n kube-system deploy/aws-load-balancer-controller` |
| Pods `CrashLoopBackOff`, log shows `APPLICATION FAILED TO START` on `app.security.api-password (API_PASSWORD)` | `API_PASSWORD` (or `OPS_PASSWORD`) is missing from the Secret, or has no `{id}` prefix. The message never shows the value. `kubectl get secret flight-ops-secret -n flight-ops -o jsonpath='{.data}'` should list `DB_PASSWORD`, `API_PASSWORD`, `OPS_PASSWORD` |
| Pods `CrashLoopBackOff`, Flyway reports `password authentication failed` | `DB_PASSWORD` in the Secret does not match the database |
| Pods `CrashLoopBackOff`, log shows `app.security.api-password cannot be verified by the configured DelegatingPasswordEncoder` | the password carries an algorithm id no encoder verifies, such as `{bcrpyt}`, and the log adds `There is no password encoder mapped for the id`. An `{argon2}` or `{scrypt}` hash stops startup the same way, because the build leaves out BouncyCastle |
| Every API call returns 401, log warns `Encoded password does not look like BCrypt` | the Secret still holds `{bcrypt}REPLACE_ME` from `deploy/k8s/secret.example.yaml`. Recreate it with a real hash |
| `kubectl get hpa` shows `<unknown>/70%` | metrics-server is not installed. `aws eks describe-addon --cluster-name flight-ops-cluster --addon-name metrics-server` |
| `up.sh` stops at step 1 on the JDK | the Maven wrapper does not see JDK 21. Point `JAVA_HOME` at `openjdk@21` |
| `up.sh` stops at step 1: `up.sh needs eksctl 0.184.0 or later` | an older eksctl installs the cluster's networking addons self-managed, and step 4's re-run looks them up as EKS addons. `brew upgrade eksctl` |
| `up.sh` stops at step 4: `cluster flight-ops-cluster is not ACTIVE` | the cluster is `FAILED` or `DELETING`, or still not `ACTIVE` after 20 minutes. The message prints the `describe-cluster` command to check it |
| `up.sh` stops at step 4: `addon vpc-cni did not become ACTIVE` or `node group ng-1 did not become ACTIVE` | the addon is `CREATE_FAILED` or `DEGRADED`, or the node group is `CREATE_FAILED`, or the wait ran out (10 minutes for vpc-cni, 40 for the node group). The message prints the command that shows its health. A re-run does not replace a `CREATE_FAILED` node group: delete it with `eksctl delete nodegroup --cluster flight-ops-cluster --name ng-1 --region ap-south-1 --wait`, then re-run |
| `up.sh` stops at step 4: `could not create addon <name>`, `could not associate an IAM OIDC provider` or `could not create node group ng-1` | EKS or eksctl refused the create. Its own error is printed just above |
| `up.sh` stops at step 4: `could not read addon <name>`, `could not read the OIDC issuer of flight-ops-cluster`, `could not list the IAM OIDC providers` or `could not read node group ng-1` | a lookup failed for another reason than "not found", such as throttling or a missing permission. Nothing is created, so a failed call is never taken for a missing resource |
| `up.sh` stops at step 4: `cluster flight-ops-cluster has no OIDC issuer` | `describe-cluster` answered, but with no `https://` issuer URL, which an `ACTIVE` EKS cluster always has. Check it with `aws eks describe-cluster --region ap-south-1 --name flight-ops-cluster --query cluster.identity.oidc.issuer` |
| `up.sh` stops at step 5: `has no AmazonEKSEditPolicy scoped to namespace/flight-ops` | the policy association was refused and is not there. The message prints the `list-associated-access-policies` command to check it |
| `up.sh` stops at step 6 on the data stack's status | `ROLLBACK_COMPLETE` or `DELETE_FAILED` cannot be used: delete the stack and re-run (the message prints both commands). A status ending `_IN_PROGRESS`: wait, then re-run. A status read that fails for another reason also stops the run, so a throttled call is never taken for "no stack" |
| `up.sh` stops at step 9: `the data stack already existed, so the database password is not available here` | the run that created the data stack stopped before step 9 wrote the Secret. Delete the data stack and re-run, or set a new password with the `modify-db-instance` command the message prints and re-run from the same shell |
| `up.sh` waits 30 minutes at step 10, then stops | CI never created the deployment. Check the workflow run: the deploy job is skipped unless `DEPLOY_ENABLED` is `true` and the run is on `main` |
| Pods run but nothing reaches SQS | IRSA is not attached. `kubectl describe pod` should show `AWS_WEB_IDENTITY_TOKEN_FILE` |
| Connection timeouts to RDS | the security group admits the cluster SG and the shared node SG. Confirm with `aws ec2 describe-security-groups` that the ids in `data.yaml`'s parameters match the live cluster |
| Pods `CrashLoopBackOff`, Flyway reports `SSL error:`, `could not be verified by hostnameverifier` or `Could not open SSL root certificate file` | the driver refused the database's certificate, because `DB_URL` sets `sslmode=verify-full`. `SSL error:` with `PKIX path building failed`: the certificate does not chain to a CA in `certs/rds-global-bundle.pem`, usually because the instance moved to a CA the committed bundle does not hold. Refresh the bundle as [doc/DEPLOYMENT.md](../../doc/DEPLOYMENT.md#the-database-connection) says, and deploy the new image. The host name message: `DB_URL` names a host the certificate does not, such as a custom DNS name, so use the `JdbcUrl` output as it is. The root certificate message: `DB_URL` has `sslmode=verify-full` but no `sslrootcert`, so the driver looked for `~/.postgresql/root.crt`. Set `DB_URL` to the whole `JdbcUrl` output. If the message names `/app/certs/rds-global-bundle.pem`, the running image lacks the bundle or `DB_URL` has a wrong path: check the image tag and the path in `DB_URL` |
| `eksctl delete cluster` fails after 20 min | the data stack is still up. Delete it, then re-run `down.sh` |
| CI stops at "Is this commit already in ECR?" | `describe-images` failed with something other than `ImageNotFoundException`, usually a missing `ecr:DescribeImages` on the CI role. The step log shows the CLI's message |
| CI stops at "Configure AWS credentials (OIDC)" with `Not authorized to perform sts:AssumeRoleWithWebIdentity` | the role did not accept the token. Its trust policy pins `sub` to `repo:<GitHubOwner>/<GitHubRepo>:ref:refs/heads/main`, with the owner and repo `foundation.yaml` was deployed with (default `smit-lakhani-13/flight-ops-services`). A wrong `AWS_ACCOUNT_ID` secret gives the same message |
| CI's `kubectl` says `You must be logged in to the server (Unauthorized)` or `Forbidden` | Unauthorized: the CI role has no access entry on the cluster. Forbidden: the entry exists, but `AmazonEKSEditPolicy` is not associated with `namespace/flight-ops`. Re-run `up.sh`, whose step 5 grants both, or check with `aws eks list-associated-access-policies --cluster-name flight-ops-cluster --principal-arn <role arn>` |

## The files

| File | What it is |
|---|---|
| `lib.sh` | shared helpers: pinned region, resource names, logging, typed confirmations, state file, and `up.sh`'s checks |
| `up.sh` | creates everything, in order, idempotently |
| `down.sh` | deletes everything, in reverse order, then proves it |
| `cost-check.sh` | daily cost by service, month-to-date, forecast, budget status |
| `render-aws.sh` | renders `deploy/k8s/overlays/aws` with the four environment values filled in; used by CI and by hand |
| `ecr-image-exists.sh` | prints `exists=true` or `exists=false` for one image tag, and fails on any other error; the deploy job's "Is this commit already in ECR?" step runs it |
| `selftest.sh` | runs `down.sh`, `cost-check.sh`, `ecr-image-exists.sh` and `up.sh`'s checks in `lib.sh` against stub `aws`, `kubectl`, `helm`, `eksctl`, `sleep` and `mvnw` commands; CI's infra-lint job runs it |
| `foundation.yaml` | ECR, GitHub OIDC provider and role, the SQS publish policy, two budgets |
| `data.yaml` | RDS PostgreSQL, its subnet group and security group |
| `cluster.yaml` | the eksctl cluster: Kubernetes version pin, one NAT gateway, OIDC for IRSA, and the managed node group `lib.sh` names; `up.sh` passes it to `eksctl` with `-f` |

`cluster.yaml` sits beside the scripts that use it, because `eksctl` takes its
config from `-f` and has no default location. The SAM template is
`lambda/template.yaml`, beside the module it deploys, and SAM resolves its
`CodeUri` against the template's directory. `up.sh` builds the Lambda with
`./mvnw -B -q -f lambda/pom.xml clean package` and deploys the jar with
`sam deploy --template-file lambda/template.yaml`, not `sam build`: SAM builds
in a scratch copy of `lambda/`, where the tests cannot find `../contracts`.

The scripts keep their state in `deploy/aws/.state/flight-ops.env`: the account
id, the ECR repository URI, the queue URL, the JDBC URL and the load balancer
hostname. It is gitignored and holds no passwords. Once the sweep passes
clean, `down.sh` renames it to `flight-ops.env.<account id>.done`.
