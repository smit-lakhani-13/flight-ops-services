# 9. eksctl, SAM and kustomize rather than Terraform

Status: accepted (recorded 2026-09-22, decision taken with the deployment tooling)

## Context

The repository needs a reproducible way to create its AWS infrastructure: a
Kubernetes cluster, a PostgreSQL instance, a queue, a Lambda, a container
registry, and the IAM that binds them. That is four quite different kinds of
resource and there is no single obvious tool for all of them.

The constraint that shapes the answer is not technical. This infrastructure is
created for a demonstration lasting one to two weeks, by one person, on one
account, and then deleted. It has no second environment, no team, no drift to
reconcile, and its most important property is that a tired operator can delete
all of it and *know* it is gone — the running cost is $7.72/day and the
30-day cost of forgetting is $273.

## Decision

Four tools, each doing the part it is best at, orchestrated by one shell script.

| Layer | Tool | File |
|---|---|---|
| Account-level: ECR, GitHub OIDC, CI role, budgets | CloudFormation | `deploy/aws/foundation.yaml` |
| Cluster, VPC, node group, IRSA | eksctl | `cluster.yaml` |
| Queue, DLQ, table, Lambda | AWS SAM | `template.yaml` |
| Database | CloudFormation | `deploy/aws/data.yaml` |
| Kubernetes manifests | kustomize | `k8s/base`, `k8s/overlays/aws` |
| Order, idempotency, secrets, teardown | bash | `deploy/aws/up.sh`, `down.sh` |

Terraform is not used. Helm is used to install the AWS Load Balancer
Controller, which is published as a chart, and not for the application.

## Consequences

* **eksctl produces a correct EKS cluster in one file.** The equivalent
  Terraform is the `terraform-aws-eks` module, which is roughly 4,000 lines of
  someone else's HCL plus a provider version matrix, or a hand-written VPC,
  subnets, route tables, NAT gateway, node group, launch template and OIDC
  provider. eksctl's `cluster.yaml` is 160 lines, most of it comments.
* **SAM understands Lambda's build.** `sam build` compiles the Java module and
  packages it; `sam local invoke` runs the handler against a fixture before
  anything is deployed. Terraform would need the artefact built and uploaded by
  something else first, which is a second tool regardless.
* **CloudFormation for the two remaining stacks** keeps the tool count at one
  for everything eksctl and SAM do not cover, and both are already
  CloudFormation underneath — so `aws cloudformation list-stacks` shows the
  whole system, and the teardown sweep has one place to look.
* **The real cost is state.** Terraform's state file is what makes `terraform
  destroy` reliable: it knows exactly what it created. Here that job is done by
  resource tags (`Project=flight-ops` on everything) and by `down.sh`'s closing
  sweep, which queries thirteen services by name and by tag and exits non-zero
  if anything survives. That is a weaker guarantee than state and a stronger one
  than a runbook, and it is checkable from a fresh shell with no local file.
* **No plan step.** `terraform plan` is genuinely valuable and is given up here.
  The mitigation is that `up.sh` is idempotent, prints the cost before the first
  billable step, and requires a typed confirmation.
* **This choice does not generalise.** With a second environment, more than one
  engineer, or infrastructure that outlives the week, the state file and the
  plan step stop being overhead and start being the reason to use the tool. The
  honest statement is that Terraform is the right answer for production and the
  wrong answer for this.

## Alternatives considered

* **Terraform for everything.** Correct for a long-lived estate. Here it is a
  state backend to create, a module tree to pin, and a `terraform destroy` that
  still leaves the Lambda artefact build to another tool.
* **AWS CDK.** Real types and real tests over CloudFormation, in Java, which
  would match the repository. Rejected on bootstrap: `cdk bootstrap` creates a
  durable stack, bucket and roles in the account, which is exactly the kind of
  leftover the teardown is trying to avoid.
* **`eksctl` for the database too.** It does not create RDS.
* **A Helm chart for the application.** Templating for one application with one
  environment, when kustomize's overlay expresses the same four substitutions
  without a template language. Helm earns its cost when a chart is published
  for others to configure.
* **Console clicking, documented in a runbook.** Faster the first time and
  unreproducible every time after, with nothing to lint and nothing to diff.
