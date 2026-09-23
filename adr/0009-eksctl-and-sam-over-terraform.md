# 9. eksctl, SAM and kustomize rather than Terraform

Status: accepted (recorded 2026-09-22, decision taken with the deployment
tooling; `cluster.yaml` and `template.yaml` date from `4a9a5b9`, and the rest
landed in `92922cd`)

## Context

The repository needs a reproducible way to create its AWS infrastructure: a
Kubernetes cluster, a PostgreSQL instance, a queue, a Lambda, a container
registry, and the IAM that binds them. Those are four quite different kinds of
resource, and no single tool is the obvious fit for all of them.

The constraint that shapes the answer is not technical. The infrastructure is
meant for a demonstration lasting one to two weeks, run by one person on one
account, and deleted afterwards. There is no second environment, no team and no
drift to reconcile. Its most important property is that a tired operator can
delete all of it and *know* it is gone. The running cost is $7.72/day, and the
30-day cost of forgetting is $273.

## Decision

I split the work across four tools, each doing the part it is best at, and one
shell script orchestrates them.

| Layer | Tool | File |
|---|---|---|
| Account-level: ECR, GitHub OIDC, CI role, budgets | CloudFormation | `deploy/aws/foundation.yaml` |
| Cluster, VPC, node group, IRSA | eksctl | `cluster.yaml` |
| Queue, DLQ, table, Lambda | AWS SAM | `template.yaml` |
| Database | CloudFormation | `deploy/aws/data.yaml` |
| Kubernetes manifests | kustomize | `k8s/base`, `k8s/overlays/aws` |
| Order, idempotency, secrets, teardown | bash | `deploy/aws/up.sh`, `down.sh` |

I do not use Terraform. I use Helm only to install the AWS Load Balancer
Controller, which is published as a chart. The application has no chart.

## Consequences

* **One file for the cluster.** With eksctl, a correct EKS cluster comes from
  one file. The Terraform equivalent is the `terraform-aws-eks` module: roughly
  4,000 lines of someone else's HCL, plus a provider version matrix. The other
  route is a hand-written VPC, subnets, route tables, NAT gateway, node group,
  launch template and OIDC provider. `cluster.yaml` is about 80 lines, and
  fewer than half of them are comments.

* **SAM understands Lambda's wiring.** Maven builds the shaded jar, and
  `CodeUri` in `template.yaml` points at it.
  `sam deploy --template-file template.yaml` uploads the jar and deploys the
  stack. The `Events` shorthand and a policy template wire the queue, the event
  source mapping and the IAM role in a few lines. `sam local invoke` runs the
  handler against a fixture before anything is deployed. Terraform would need
  each of those as a separate resource, and the jar would come from Maven
  either way.

* **CloudFormation for the rest.** The two remaining stacks are plain
  CloudFormation. That keeps the tool count at one for everything eksctl and
  SAM do not cover, and both of those are CloudFormation underneath too. So
  `aws cloudformation list-stacks` shows the whole system, and the teardown
  sweep has one place to look.

* **The real cost is state.** Terraform's state file is what makes
  `terraform destroy` reliable, because it knows what it created. Here, resource
  tags (`Project=flight-ops` on everything) do that job, together with the
  closing sweep in `down.sh`. The sweep runs fourteen checks by name and by
  tag. It exits non-zero if anything survives, or if a check could not be
  answered. This is a weaker guarantee than state and a stronger one than a
  runbook, and it can be checked from a fresh shell with no local file.

* **No plan step.** `terraform plan` is valuable, and I give it up here. To make
  up for it, `up.sh` is idempotent, prints the cost before the first billable
  step, and requires a typed confirmation.

* **This choice does not generalise.** With a second environment, more than one
  engineer, or infrastructure that outlives the week, the state file and the
  plan step stop being overhead. They become the reason to use the tool.
  Terraform is the right answer for production and the wrong answer for this.

**Correction (2026-09-23).** The second bullet used to say that `sam build`
compiles the Java module and packages it. It could not produce the handler jar.
SAM builds in a scratch copy of `lambda/`, where the Lambda tests cannot find
`../events` and `../contracts`. The deploy path now builds with Maven
(`deploy/aws/up.sh` step 3), and the bullet gives the reason that is left. The
old bullet, and the Terraform entry below, also counted the build against
Terraform. Both tools now take the jar from Maven, so neither does. The
decision stands on the other reasons. Only the build reason was wrong.

## Alternatives considered

* **Terraform for everything.** Correct for a long-lived estate. Here it is a
  state backend to create, a module tree to pin, and the Lambda's event source
  mapping and IAM written out resource by resource.

* **AWS CDK.** Real types and real tests over CloudFormation, in Java, which
  would match the repository. I rejected it because of bootstrap:
  `cdk bootstrap` creates a durable stack, bucket and roles in the account.
  Those are the kind of leftovers the teardown is trying to avoid.

* **`eksctl` for the database too.** It does not create RDS.

* **An application Helm chart.** Templating for one application with one
  environment, where kustomize's overlay expresses the same four substitutions
  without a template language. Helm earns its cost when a chart is published
  for others to configure.

* **Console clicking and a runbook.** Faster the first time and unreproducible
  every time after, with nothing to lint and nothing to diff.
