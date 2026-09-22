#!/usr/bin/env bash
# Creates the whole demo on AWS, in order, idempotently.
#
#   ALERT_EMAIL=you@example.com ./deploy/aws/up.sh
#
# Roughly 50 minutes of wall clock, nearly all of it waiting for EKS (~20 min)
# and RDS (~10 min). Every step is safe to re-run: each checks whether its
# resource already exists before creating it, so an interrupted run is resumed
# by running it again rather than by unpicking it by hand.
#
# It costs money from step 4 onwards. Step 1 prints the rate and asks.
#
# What this script does NOT do: build or push the image, and apply the
# Deployment. Those are CI's job (.github/workflows/build-and-deploy.yml),
# because the image tag must be a commit SHA and the thing that knows the
# commit is the thing that built it. This script prints the three values CI
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
require_tool aws eksctl kubectl helm sam openssl htpasswd envsubst
[ -n "${ALERT_EMAIL:-}" ] || die "ALERT_EMAIL is required (budget alerts go there). Example:
    ALERT_EMAIL=you@example.com $0"

ACCOUNT_ID=$(require_credentials)
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

  Budgets will alert $ALERT_EMAIL at 50/80/100% of \$60/month and 80% of \$12/day.
  Confirm the subscription email when it arrives, or the alerts never fire.

  Tear it all down with:  $here/down.sh

COST
confirm "This will start billing. Nothing else in this script asks again."

# ---------------------------------------------------------------------------
step "2/12  Foundation stack — ECR, OIDC trust, deploy role, budgets"
# ---------------------------------------------------------------------------
# An account may hold exactly one OIDC provider per URL, so creating a second
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
# Built and deployed before the cluster on purpose: it is the cheap half, it
# has no dependency on EKS, and its queue URL is an input the cluster needs.
(
    cd "$repo"
    sam build
    sam deploy \
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
    ok "cluster already exists"
else
    eksctl create cluster -f "$repo/cluster.yaml"
fi

# eksctl does not always set the upgrade policy, and the default on some paths
# is EXTENDED. Extended support costs an extra \$0.50/hr in Mumbai — six times
# the control plane itself — and it applies silently the moment the version
# leaves standard support. STANDARD means the cluster refuses to enter extended
# support instead of quietly billing for it.
if aws eks update-cluster-config --name "$CLUSTER_NAME" \
        --upgrade-policy supportType=STANDARD >/dev/null 2>&1; then
    ok "upgrade policy pinned to STANDARD support"
else
    log "upgrade policy already STANDARD"
fi

aws eks update-kubeconfig --name "$CLUSTER_NAME" --region "$AWS_REGION"

# ---------------------------------------------------------------------------
step "5/12  Cluster access for the CI role"
# ---------------------------------------------------------------------------
# API access entries, not aws-auth ConfigMap edits. The ConfigMap is the old
# mechanism and a malformed edit locks everyone out of the cluster with no way
# back in short of recreating it.
DEPLOY_ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/github-actions-deploy"
aws eks create-access-entry \
    --cluster-name "$CLUSTER_NAME" \
    --principal-arn "$DEPLOY_ROLE_ARN" \
    --type STANDARD >/dev/null 2>&1 || log "access entry already exists"

# Scoped to one namespace. The CI role can roll out the application and cannot
# touch kube-system, the LB controller, or another team's namespace — which is
# what makes "CI has cluster access" an acceptable sentence.
aws eks associate-access-policy \
    --cluster-name "$CLUSTER_NAME" \
    --principal-arn "$DEPLOY_ROLE_ARN" \
    --policy-arn arn:aws:eks::aws:cluster-access-policy/AmazonEKSEditPolicy \
    --access-scope "type=namespace,namespaces=$NAMESPACE" >/dev/null 2>&1 \
    || log "access policy already associated"
ok "github-actions-deploy may edit namespace/$NAMESPACE and nothing else"

# ---------------------------------------------------------------------------
step "6/12  Database — the ~10 minute step"
# ---------------------------------------------------------------------------
VPC_ID=$(eksctl_stack_output VPC)
PRIVATE_SUBNETS=$(eksctl_stack_output SubnetsPrivate)
CLUSTER_SG=$(eksctl_stack_output ClusterSecurityGroupId)
SHARED_NODE_SG=$(eksctl_stack_output SharedNodeSecurityGroup)
[ -n "$VPC_ID" ] && [ -n "$PRIVATE_SUBNETS" ] || die "could not read the eksctl stack outputs"

if stack_exists "$DATA_STACK"; then
    ok "data stack already exists — not touching the password"
else
    # Generated here, used twice (the RDS parameter and the Kubernetes
    # Secret), and never written to disk. Losing it means replacing the
    # instance, which for a demo database is a two-command fix.
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
    curl -fsSL -o /tmp/lbc-iam-policy.json \
        "https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/${LBC_POLICY_TAG}/docs/install/iam_policy.json"
    aws iam create-policy \
        --policy-name AWSLoadBalancerControllerIAMPolicy \
        --policy-document file:///tmp/lbc-iam-policy.json >/dev/null
    ok "created AWSLoadBalancerControllerIAMPolicy from $LBC_POLICY_TAG"
fi

# Here eksctl DOES create the ServiceAccount, because nothing else owns it —
# it lives in kube-system and is referenced by the Helm chart.
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
if aws eks create-addon --cluster-name "$CLUSTER_NAME" \
        --addon-name metrics-server >/dev/null 2>&1; then
    ok "metrics-server addon requested"
else
    log "metrics-server already installed"
fi

# ---------------------------------------------------------------------------
step "9/12  Namespace and secrets"
# ---------------------------------------------------------------------------
kubectl apply -f "$repo/k8s/namespace.yaml"

if kubectl get secret flight-ops-secret -n "$NAMESPACE" >/dev/null 2>&1; then
    ok "secret already exists — leaving it alone"
    API_PASSWORD_PLAIN='(unchanged — see your earlier run)'
    OPS_PASSWORD_PLAIN='(unchanged)'
else
    [ -n "${DB_PASSWORD:-}" ] || die "the data stack already existed, so the database password is
    not available here. Either delete the data stack and re-run, or create the
    secret by hand from k8s/secret.example.yaml."

    API_PASSWORD_PLAIN=$(openssl rand -base64 18 | tr -d '/+= ')
    OPS_PASSWORD_PLAIN=$(openssl rand -base64 18 | tr -d '/+= ')

    # bcrypt, cost 10. The service's ApiSecurityProperties REJECTS a value with
    # no {id} prefix at startup, so a hash pasted without it fails the pod
    # rather than silently storing a plaintext password.
    api_hash="{bcrypt}$(htpasswd -bnBC 10 "" "$API_PASSWORD_PLAIN" | tr -d ':\n')"
    ops_hash="{bcrypt}$(htpasswd -bnBC 10 "" "$OPS_PASSWORD_PLAIN" | tr -d ':\n')"
    # shellcheck disable=SC2016  # '$2' is bcrypt's version marker in a glob,
    # not a variable: single quotes are exactly what this line needs.
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
    ok "secret created (the passwords are printed once, at the end)"
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

log "waiting for the deployment to appear and become available (up to 20 min)..."
kubectl wait --for=condition=available deployment/flight-ops \
    -n "$NAMESPACE" --timeout=20m \
    || die "the deployment did not become available. Diagnose with:
    kubectl get pods -n $NAMESPACE -o wide
    kubectl logs -n $NAMESPACE -l app=flight-ops --tail=100 --all-containers
    kubectl get events -n $NAMESPACE --sort-by=.lastTimestamp | tail -30"
ok "pods are serving"

# ---------------------------------------------------------------------------
step "11/12  Ingress and the public URL"
# ---------------------------------------------------------------------------
kubectl apply -k "$repo/k8s/components/ingress" -n "$NAMESPACE" 2>/dev/null \
    || kubectl apply -f "$repo/k8s/components/ingress/ingress.yaml" -n "$NAMESPACE"

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

log "waiting for the ALB target group to report healthy..."
until curl -fsS -o /dev/null "http://$ALB_HOST/actuator/health"; do sleep 5; done
ok "health check passes through the load balancer"

# ---------------------------------------------------------------------------
step "12/12  Proving it works, over the internet"
# ---------------------------------------------------------------------------
if [ "$API_PASSWORD_PLAIN" = '(unchanged — see your earlier run)' ]; then
    warn "skipping demo.sh — the API password is from an earlier run and is not known here"
else
    BASE="http://$ALB_HOST" \
    AUTH="-u api:$API_PASSWORD_PLAIN" \
    OPS_AUTH="-u ops:$OPS_PASSWORD_PLAIN" \
        "$repo/demo.sh" --fast
fi

cat <<SUMMARY

  ${C_BOLD}Done.${C_RESET}

    URL          http://$ALB_HOST
    Swagger      http://$ALB_HOST/swagger-ui.html
    api user     api / $API_PASSWORD_PLAIN
    ops user     ops / $OPS_PASSWORD_PLAIN

  These passwords are printed once and are not stored anywhere outside the
  cluster. They are in the Kubernetes Secret; read them back with:
    kubectl get secret flight-ops-secret -n $NAMESPACE -o jsonpath='{.data.API_PASSWORD}' | base64 -d
  (that returns the bcrypt hash, not the password — hence "once".)

  Running cost: about \$7.72/day. Check it tomorrow with:
    $here/cost-check.sh

  ${C_BOLD}Tear it down with:${C_RESET}
    $here/down.sh

SUMMARY
