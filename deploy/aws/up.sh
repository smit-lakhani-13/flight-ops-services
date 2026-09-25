#!/usr/bin/env bash
# Creates the whole demo on AWS, in order, idempotently.
#
#   ALERT_EMAIL=you@example.com ./deploy/aws/up.sh
#
# Roughly 50 minutes of wall clock, nearly all of it waiting for EKS (~20 min)
# and RDS (~10 min). Every step checks whether its resource already exists
# before creating it, so an interrupted run is resumed by running it again.
# Step 4 also finishes a cluster that an interrupted eksctl left part-built.
# The database password lives only in this shell from step 6 until step 9
# writes the Secret. A run that stops in between stops again at step 9, and
# says how to set a new password.
#
# It costs money from step 4 onwards. Step 1 prints the rate and asks.
#
# CI builds and pushes the image and applies the Deployment
# (.github/workflows/build-and-deploy.yml): the image tag is a commit SHA, and
# the job that built the commit knows it. This script prints the values CI
# needs and waits.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$here/../.." && pwd)
# shellcheck source=deploy/aws/lib.sh
. "$here/lib.sh"

# The EKS version lives in cluster.yaml, once. It is not repeated here.
LBC_CHART_VERSION=3.5.0          # aws-load-balancer-controller Helm chart
LBC_POLICY_TAG=v3.5.0            # the matching iam_policy.json tag

# ---------------------------------------------------------------------------
step "1/12  Preflight, and what this is about to cost"
# ---------------------------------------------------------------------------
require_tool aws eksctl kubectl helm sam openssl htpasswd
[ -n "${ALERT_EMAIL:-}" ] || die "ALERT_EMAIL is required (budget alerts go there). Example:
    ALERT_EMAIL=you@example.com $0"

# Step 3 builds the Lambda jar with the Maven wrapper.
require_jdk21 "$repo/mvnw"
require_eksctl

ACCOUNT_ID=$(require_credentials) || exit 1
ok "account $ACCOUNT_ID, region $AWS_REGION"

profile_region=$(aws configure get region 2>/dev/null || true)
if [ -n "$profile_region" ] && [ "$profile_region" != "$AWS_REGION" ]; then
    warn "your AWS profile defaults to '$profile_region'; these scripts pin '$AWS_REGION'."
    warn "That is intentional — but remember it when you go looking in the console."
fi

cat <<COST

  This creates, in ap-south-1, on-demand:

    EKS control plane        \$0.10/hr          \$2.40/day
    2 x t3.medium nodes      \$0.0448/hr each   \$2.15/day
    NAT gateway              \$0.056/hr + data  \$1.43/day at 1.5 GB/day
    Application Load Balancer \$0.0239/hr + LCU \$0.62/day
    RDS db.t4g.micro         \$0.021/hr         \$0.50/day
    EBS + public IPv4        —                  \$0.62/day
    Lambda, SQS, DynamoDB    free tier at this volume
                                                ---------
                                                \$7.72/day

    7 days  ≈ \$54   (\$64 with 18% GST ≈ ₹6,100)
   10 days  ≈ \$77   (\$91  ≈ ₹8,700)
   15 days  ≈ \$116  (\$137 ≈ ₹13,100)
   30 days  ≈ \$232  (\$273 ≈ ₹26,200)   <- the number that matters if you forget

  Budgets will alert $ALERT_EMAIL at 50/80/100% of \$60/month, when the
  month's forecast passes \$60, and at 80% of \$12/day.
  Confirm the subscription email when it arrives, or the alerts never fire.

  Tear it all down with:  $here/down.sh

COST
confirm "This will start billing. The only other prompts are the two hand-offs at steps 9 and 10."

# ---------------------------------------------------------------------------
step "2/12  Foundation stack — ECR, OIDC trust, deploy role, budgets"
# ---------------------------------------------------------------------------
# An account holds at most one OIDC provider per URL, so creating a second
# fails. Detect an existing one and hand it to the template instead.
existing_oidc=$(aws iam list-open-id-connect-providers \
    --query "OpenIDConnectProviderList[?contains(Arn, 'token.actions.githubusercontent.com')].Arn" \
    --output text 2>/dev/null || true)
[ "$existing_oidc" = "None" ] && existing_oidc=""
[ -n "$existing_oidc" ] && log "reusing existing GitHub OIDC provider"

aws cloudformation deploy \
    --stack-name "$FOUNDATION_STACK" \
    --template-file "$here/foundation.yaml" \
    --capabilities CAPABILITY_NAMED_IAM \
    --parameter-overrides \
        "AlertEmail=$ALERT_EMAIL" \
        "ExistingOidcProviderArn=$existing_oidc" \
    --tags Project=flight-ops \
    --no-fail-on-empty-changeset
ok "foundation stack up"

ECR_URI=$(stack_output "$FOUNDATION_STACK" EcrRepositoryUri)
SQS_POLICY_ARN=$(stack_output "$FOUNDATION_STACK" SqsPublishPolicyArn)
state_set ACCOUNT_ID "$ACCOUNT_ID"
state_set ECR_URI "$ECR_URI"

# Cost allocation tags take up to 24 hours to activate and the call fails in a
# brand-new account that has never seen the tag. Neither is a reason to stop.
if aws ce update-cost-allocation-tags-status \
        --cost-allocation-tags-status TagKey=Project,Status=Active >/dev/null 2>&1; then
    ok "cost allocation tag 'Project' activated (visible in Cost Explorer within 24h)"
else
    warn "could not activate the 'Project' cost allocation tag yet — harmless, retry tomorrow"
fi

# ---------------------------------------------------------------------------
step "3/12  Lambda stack — SQS, DLQ, DynamoDB, the consumer"
# ---------------------------------------------------------------------------
# Before the cluster: it is the cheap half, it does not depend on EKS, and the
# cluster needs its queue URL.
#
# Maven builds the jar, not `sam build`. SAM copies only the CodeUri directory
# into a scratch directory, and the Lambda tests read ../events and
# ../contracts, so they fail there. template.yaml points CodeUri at the shaded
# jar. sam deploy prefers .aws-sam/build/template.yaml when one exists, so a
# stale build from an earlier `sam build` is removed first.
(
    cd "$repo"
    rm -rf .aws-sam
    ./mvnw -B -q -f lambda/pom.xml clean package
    sam deploy \
        --template-file template.yaml \
        --stack-name "$SAM_STACK" \
        --resolve-s3 \
        --capabilities CAPABILITY_IAM \
        --no-confirm-changeset \
        --no-fail-on-empty-changeset \
        --tags Project=flight-ops
)
SQS_QUEUE_URL=$(stack_output "$SAM_STACK" QueueUrl)
[ -n "$SQS_QUEUE_URL" ] || die "the SAM stack has no QueueUrl output"
state_set SQS_QUEUE_URL "$SQS_QUEUE_URL"
ok "queue $SQS_QUEUE_URL"

# ---------------------------------------------------------------------------
step "4/12  EKS cluster — this is the ~20 minute step"
# ---------------------------------------------------------------------------
if eksctl get cluster --name "$CLUSTER_NAME" >/dev/null 2>&1; then
    ok "cluster already exists — checking its addons, OIDC provider and node group"
    complete_cluster "$repo/cluster.yaml"
else
    eksctl create cluster -f "$repo/cluster.yaml"
fi

# eksctl does not always set the upgrade policy, and the default on some paths
# is EXTENDED. Extended support bills the control plane at six times the
# standard rate from the moment the version leaves standard support, with no
# error anywhere. STANDARD makes the cluster refuse extended support instead.
#
# The update call is best-effort: it fails with ResourceInUseException while
# the cluster is still settling, and an old CLI does not know the flag. The
# policy is then read back, because a failed call and a policy that was
# already STANDARD look the same from the exit code.
aws eks update-cluster-config --name "$CLUSTER_NAME" \
    --upgrade-policy supportType=STANDARD >/dev/null 2>&1 || true

support_type=$(aws eks describe-cluster --name "$CLUSTER_NAME" \
    --query 'cluster.upgradePolicy.supportType' --output text 2>/dev/null || true)
case "$support_type" in
    STANDARD)
        ok "upgrade policy is STANDARD — the cluster cannot enter extended support"
        ;;
    EXTENDED)
        warn "upgrade policy is EXTENDED. The control plane bills \$0.60/hour"
        warn "instead of \$0.10 the moment this version leaves standard support."
        warn "Fix it before leaving this running:"
        warn "  aws eks update-cluster-config --name $CLUSTER_NAME \\"
        warn "    --upgrade-policy supportType=STANDARD"
        ;;
    *)
        warn "could not read the upgrade policy (got '${support_type:-nothing}')."
        warn "Check it by hand: aws eks describe-cluster --name $CLUSTER_NAME \\"
        warn "  --query cluster.upgradePolicy.supportType"
        ;;
esac

aws eks update-kubeconfig --name "$CLUSTER_NAME" --region "$AWS_REGION"

# ---------------------------------------------------------------------------
step "5/12  Cluster access for the CI role"
# ---------------------------------------------------------------------------
# API access entries, not aws-auth ConfigMap edits. The ConfigMap is the old
# mechanism and a malformed edit locks everyone out of the cluster with no way
# back in short of recreating it.
#
# Scoped to one namespace. The CI role can roll out the application and cannot
# touch kube-system, the LB controller, or another namespace.
grant_namespace_access "arn:aws:iam::${ACCOUNT_ID}:role/github-actions-deploy" \
    arn:aws:eks::aws:cluster-access-policy/AmazonEKSEditPolicy "$NAMESPACE"
ok "github-actions-deploy may edit namespace/$NAMESPACE and nothing else"

# ---------------------------------------------------------------------------
step "6/12  Database — the ~10 minute step"
# ---------------------------------------------------------------------------
VPC_ID=$(eksctl_stack_output VPC)
PRIVATE_SUBNETS=$(eksctl_stack_output SubnetsPrivate)
CLUSTER_SG=$(eksctl_stack_output ClusterSecurityGroupId)
SHARED_NODE_SG=$(eksctl_stack_output SharedNodeSecurityGroup)
if [ -z "$VPC_ID" ] || [ -z "$PRIVATE_SUBNETS" ]; then
    die "could not read the eksctl stack outputs"
fi

if stack_ready "$DATA_STACK"; then
    ok "data stack already exists — not touching the password"
else
    # Generated here, used twice (the RDS parameter and the Kubernetes
    # Secret), and never written to disk. A run that loses it before step 9
    # stops there and prints the two commands that set a new one.
    DB_PASSWORD=$(openssl rand -base64 24 | tr -d '/@" =' | cut -c1-24)
    aws cloudformation deploy \
        --stack-name "$DATA_STACK" \
        --template-file "$here/data.yaml" \
        --parameter-overrides \
            "VpcId=$VPC_ID" \
            "PrivateSubnetIds=${PRIVATE_SUBNETS}" \
            "ClusterSecurityGroupId=$CLUSTER_SG" \
            "SharedNodeSecurityGroupId=$SHARED_NODE_SG" \
            "DBPassword=$DB_PASSWORD" \
        --tags Project=flight-ops \
        --no-fail-on-empty-changeset
fi
DB_URL=$(stack_output "$DATA_STACK" JdbcUrl)
require_jdbc_url "$DB_URL"
state_set DB_URL "$DB_URL"
ok "$DB_URL"

# ---------------------------------------------------------------------------
step "7/12  IRSA — the pods' AWS identity, with no access keys"
# ---------------------------------------------------------------------------
# --role-only: the ServiceAccount itself is part of the application manifests
# (k8s/base/serviceaccount.yaml), so eksctl must create the IAM role and the
# trust policy and stop there. Letting eksctl own the ServiceAccount too means
# two things claim the same object and `kubectl apply` fights it.
if aws iam get-role --role-name flight-ops-sqs-publisher >/dev/null 2>&1; then
    ok "IRSA role already exists"
else
    eksctl create iamserviceaccount \
        --cluster "$CLUSTER_NAME" \
        --namespace "$NAMESPACE" \
        --name flight-ops-sa \
        --role-name flight-ops-sqs-publisher \
        --attach-policy-arn "$SQS_POLICY_ARN" \
        --role-only \
        --approve
fi

# ---------------------------------------------------------------------------
step "8/12  AWS Load Balancer Controller and metrics-server"
# ---------------------------------------------------------------------------
# The Ingress in k8s/components/ingress does nothing without this controller:
# `kubectl get ingress` shows no ADDRESS, forever, with no error anywhere.
LBC_POLICY_ARN="arn:aws:iam::${ACCOUNT_ID}:policy/AWSLoadBalancerControllerIAMPolicy"
if ! aws iam get-policy --policy-arn "$LBC_POLICY_ARN" >/dev/null 2>&1; then
    # mktemp, not a fixed name in /tmp. Another local user could leave a
    # writable file or a symlink at that name. They could then rewrite it after
    # curl and before create-policy, so the policy would carry their text.
    policy_file=$(mktemp)
    trap 'rm -f "$policy_file"' EXIT
    curl -fsSL -o "$policy_file" \
        "https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/${LBC_POLICY_TAG}/docs/install/iam_policy.json"
    aws iam create-policy \
        --policy-name AWSLoadBalancerControllerIAMPolicy \
        --policy-document "file://$policy_file" >/dev/null
    rm -f "$policy_file"
    trap - EXIT
    ok "created AWSLoadBalancerControllerIAMPolicy from $LBC_POLICY_TAG"
fi

# Here eksctl DOES create the ServiceAccount, because nothing else owns it: it
# lives in kube-system and the Helm chart refers to it.
eksctl create iamserviceaccount \
    --cluster "$CLUSTER_NAME" \
    --namespace kube-system \
    --name aws-load-balancer-controller \
    --attach-policy-arn "$LBC_POLICY_ARN" \
    --override-existing-serviceaccounts \
    --approve

helm repo add eks https://aws.github.io/eks-charts >/dev/null 2>&1 || true
helm repo update >/dev/null
helm upgrade --install aws-load-balancer-controller eks/aws-load-balancer-controller \
    --namespace kube-system \
    --version "$LBC_CHART_VERSION" \
    --set "clusterName=$CLUSTER_NAME" \
    --set serviceAccount.create=false \
    --set serviceAccount.name=aws-load-balancer-controller \
    --wait

# The EKS-managed addon rather than the upstream manifest: AWS resolves the
# version against the cluster version, so there is no pinned URL here to go
# stale. Without it the HPA reports <unknown>/70% and never scales.
ensure_metrics_server

# ---------------------------------------------------------------------------
step "9/12  Namespace and secrets"
# ---------------------------------------------------------------------------
kubectl apply -f "$repo/k8s/namespace.yaml"

if kubectl get secret flight-ops-secret -n "$NAMESPACE" >/dev/null 2>&1; then
    ok "secret already exists — leaving it alone"
    API_PASSWORD_PLAIN='(unchanged — see your earlier run)'
    OPS_PASSWORD_PLAIN='(unchanged)'
else
    # An exported DB_PASSWORD gets here on a re-run: step 6 sets it only when
    # it creates the data stack.
    [ -n "${DB_PASSWORD:-}" ] || die "the data stack already existed, so the database password is
    not available here: it lived only in the shell of the run that created the
    stack. Delete the data stack and re-run, or set a new password on the
    database and re-run from the same shell:
    export DB_PASSWORD=\$(openssl rand -base64 24 | tr -d '/@\" =' | cut -c1-24)
    aws rds modify-db-instance --region $AWS_REGION \\
        --db-instance-identifier flight-ops-db \\
        --master-user-password \"\$DB_PASSWORD\" --apply-immediately
    ALERT_EMAIL=$ALERT_EMAIL $0"

    API_PASSWORD_PLAIN=$(openssl rand -base64 18 | tr -d '/+= ')
    OPS_PASSWORD_PLAIN=$(openssl rand -base64 18 | tr -d '/+= ')

    # bcrypt, cost 10, with the {bcrypt} prefix. ApiSecurityProperties refuses
    # a value with no {id} prefix at startup, so a hash pasted without one
    # stops the pod instead of being stored as a plaintext password.
    api_hash="{bcrypt}$(htpasswd -bnBC 10 "" "$API_PASSWORD_PLAIN" | tr -d ':\n')"
    ops_hash="{bcrypt}$(htpasswd -bnBC 10 "" "$OPS_PASSWORD_PLAIN" | tr -d ':\n')"
    # shellcheck disable=SC2016  # '$2' is bcrypt's version marker in a glob,
    # not a variable, so it stays in single quotes.
    case "$api_hash" in
        '{bcrypt}$2'*) : ;;
        *) die "htpasswd produced something that is not a bcrypt hash: ${api_hash:0:20}..." ;;
    esac

    kubectl create secret generic flight-ops-secret \
        --namespace "$NAMESPACE" \
        --from-literal=DB_PASSWORD="$DB_PASSWORD" \
        --from-literal=API_PASSWORD="$api_hash" \
        --from-literal=OPS_PASSWORD="$ops_hash" \
        --dry-run=client -o yaml | kubectl apply -f -
    ok "secret created"

    # Printed here, before anything else can fail. Only the bcrypt hash
    # reaches the cluster, so these two strings exist nowhere else once this
    # shell exits. Under `set -e`, a timed-out rollout or an ALB that never
    # goes healthy ends the run before the closing summary. Getting back in
    # would then mean replacing the Secret and restarting every pod.
    cat <<CREDS

  ${C_BOLD}Credentials — written down now, before anything else can fail.${C_RESET}
  ${C_BOLD}These are not stored anywhere outside this terminal.${C_RESET}

    api user     api / $API_PASSWORD_PLAIN      (flights:read, flights:write)
    ops user     ops / $OPS_PASSWORD_PLAIN      (ROLE_OPS, the actuator)

  The cluster holds the bcrypt hashes, not these. Reading the Secret back gives
  you \$2y\$10\$... and no way to reverse it. Save them somewhere now.

CREDS
    confirm "Copy those two passwords somewhere safe." "saved"
fi

# ---------------------------------------------------------------------------
step "10/12  Hand off to CI"
# ---------------------------------------------------------------------------
cat <<HANDOFF

  The infrastructure is ready. CI builds and deploys the application, because
  the image tag must be the commit SHA and CI is what knows it.

  In the GitHub repository settings, set:

    Secret    AWS_ACCOUNT_ID   $ACCOUNT_ID
    Variable  DEPLOY_ENABLED   true
    Variable  SQS_QUEUE_URL    $SQS_QUEUE_URL
    Variable  DB_URL           $DB_URL

  Then run the workflow (Actions -> build-and-deploy -> Run workflow on main),
  or push to main.

HANDOFF
confirm "Waiting for the rollout. Set those, start the workflow, then continue." "done"

wait_for_deployment
ok "pods are serving"

# ---------------------------------------------------------------------------
step "11/12  Ingress and the public URL"
# ---------------------------------------------------------------------------
# -f, not -k: kustomize cannot build a Component as a build root. A label or
# patch added to the Component's kustomization.yaml is therefore not applied
# here.
#
# The Ingress stays out of render-aws.sh, which renders the six resources CI
# applies on every push. This one bills from the moment it exists, so the
# operator who accepted that cost creates it once. See the header of
# k8s/components/ingress/kustomization.yaml.
kubectl apply -f "$repo/k8s/components/ingress/ingress.yaml" -n "$NAMESPACE"

log "waiting for the ALB to be provisioned (2-4 minutes)..."
ALB_HOST=""
for _ in $(seq 1 60); do
    ALB_HOST=$(kubectl get ingress flight-ops-ingress -n "$NAMESPACE" \
        -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)
    [ -n "$ALB_HOST" ] && break
    sleep 10
done
[ -n "$ALB_HOST" ] || die "no ALB hostname after 10 minutes. Check the controller:
    kubectl logs -n kube-system deploy/aws-load-balancer-controller --tail=50"
state_set ALB_HOST "$ALB_HOST"
ok "http://$ALB_HOST"

# Bounded. A security group that does not admit the ALB, or a health check on
# the wrong port, looks like "not ready yet" and never resolves. An unbounded
# loop would sit there overnight with the whole stack billing.
log "waiting for the ALB target group to report healthy (up to 5 minutes)..."
alb_healthy=0
for _ in $(seq 1 60); do
    if curl -fsS -o /dev/null --max-time 5 "http://$ALB_HOST/actuator/health"; then
        alb_healthy=1
        break
    fi
    sleep 5
done
[ "$alb_healthy" = 1 ] || die "the ALB never reported healthy. The pods passed
step 10, so this is between the load balancer and them -- usually a security
group or a target group health check on the wrong port:
    kubectl describe ingress flight-ops-ingress -n $NAMESPACE
    kubectl logs -n kube-system deploy/aws-load-balancer-controller --tail=50
    aws elbv2 describe-target-groups --output table
Nothing is torn down. Fix it and re-run, or run $here/down.sh."
ok "health check passes through the load balancer"

# ---------------------------------------------------------------------------
step "12/12  Proving it works, over the internet"
# ---------------------------------------------------------------------------
if [ "$API_PASSWORD_PLAIN" = '(unchanged — see your earlier run)' ]; then
    warn "skipping demo.sh — the API password is from an earlier run and is not known here"
else
    # `|| warn`: the deployment has already succeeded, so a failing act must
    # not fail the run or stop the summary below from printing. A command
    # followed by `||` is exempt from `set -e`.
    BASE="http://$ALB_HOST" \
    AUTH="-u api:$API_PASSWORD_PLAIN" \
    OPS_AUTH="-u ops:$OPS_PASSWORD_PLAIN" \
        "$repo/demo.sh" --fast \
        || warn "demo.sh did not finish cleanly. The infrastructure is up and the
    credentials were printed in step 9; investigate with:
        curl -i -u api:<password> http://$ALB_HOST/api/v1/flights"
fi

cat <<SUMMARY

  ${C_BOLD}Done.${C_RESET}

    URL          http://$ALB_HOST
    Swagger      http://$ALB_HOST/swagger-ui.html
    api user     api / $API_PASSWORD_PLAIN
    ops user     ops / $OPS_PASSWORD_PLAIN

  Repeated from step 9 for convenience, not as the only copy. The cluster
  stores bcrypt hashes; this reads back \$2y\$10\$... and nothing reversible:
    kubectl get secret flight-ops-secret -n $NAMESPACE -o jsonpath='{.data.API_PASSWORD}' | base64 -d

  Running cost: about \$7.72/day. Check it tomorrow with:
    $here/cost-check.sh

  ${C_BOLD}Tear it down with:${C_RESET}
    $here/down.sh

SUMMARY
