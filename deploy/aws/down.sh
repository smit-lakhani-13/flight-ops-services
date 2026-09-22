#!/usr/bin/env bash
# Deletes everything up.sh created, in the reverse order, and then proves it.
#
#   ./deploy/aws/down.sh                  # everything
#   ./deploy/aws/down.sh --keep-foundation  # leave ECR and the CI role
#
# Order matters, and the two places it matters are not obvious:
#
#   1. The Ingress goes first. Deleting the namespace with an Ingress still in
#      it removes the Kubernetes object while the controller — also being
#      deleted — never gets to delete the ALB. The load balancer survives, with
#      nothing left in the cluster pointing at it, at $0.62/day, forever.
#   2. The data stack goes before the cluster. Its security group lives in
#      eksctl's VPC, and a security group with a rule referencing it blocks the
#      VPC delete. eksctl then fails after 20 minutes with a message about a
#      dependency it cannot name.
#
# Everything is best-effort: a resource that is already gone is not an error.
# The final sweep is what decides whether this run succeeded, not the deletes.
set -uo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=deploy/aws/lib.sh
. "$here/lib.sh"

KEEP_FOUNDATION=0
[ "${1:-}" = "--keep-foundation" ] && KEEP_FOUNDATION=1

require_tool aws
ACCOUNT_ID=$(require_credentials)

step "0/9  What is about to be deleted"
cat <<PLAN

  Account $ACCOUNT_ID, region $AWS_REGION:

    the Ingress and its ALB
    the AWS Load Balancer Controller and its IAM role
    namespace $NAMESPACE, including the Secret and its passwords
    $SAM_STACK        (SQS, DLQ, DynamoDB table AND ITS DATA, the Lambda)
    $DATA_STACK          (RDS instance AND ITS DATA — no final snapshot)
    $CLUSTER_NAME       (the cluster, the nodes, the VPC, the NAT gateway)
PLAN
if [ "$KEEP_FOUNDATION" = 1 ]; then
    echo "    $FOUNDATION_STACK is KEPT (--keep-foundation): ECR images, the CI role, the budgets"
else
    echo "    $FOUNDATION_STACK  (ECR AND ALL IMAGES, the CI role, the budgets)"
fi
cat <<'PLAN'

  There is no snapshot and no backup. Both databases go with their data.

PLAN
confirm "This deletes data permanently." "delete"

# ---------------------------------------------------------------------------
step "1/9  Ingress first, so the controller can delete its own load balancer"
# ---------------------------------------------------------------------------
if kubectl get ingress flight-ops-ingress -n "$NAMESPACE" >/dev/null 2>&1; then
    alb_host=$(kubectl get ingress flight-ops-ingress -n "$NAMESPACE" \
        -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)
    kubectl delete ingress flight-ops-ingress -n "$NAMESPACE" --timeout=5m || true

    if [ -n "$alb_host" ]; then
        log "waiting for the load balancer to disappear from the AWS API..."
        for _ in $(seq 1 60); do
            found=$(aws elbv2 describe-load-balancers \
                --query "LoadBalancers[?DNSName=='$alb_host'].LoadBalancerArn" \
                --output text 2>/dev/null || true)
            if [ -z "$found" ] || [ "$found" = "None" ]; then break; fi
            sleep 10
        done
    fi
    ok "ingress and ALB gone"
else
    log "no ingress"
fi

# ---------------------------------------------------------------------------
step "2/9  The load balancer controller"
# ---------------------------------------------------------------------------
# After the Ingress, never before: uninstalling the controller first leaves
# nothing able to delete the ALB, which is the expensive mistake.
if command -v helm >/dev/null 2>&1; then
    if helm uninstall aws-load-balancer-controller -n kube-system --wait 2>/dev/null; then
        ok "controller uninstalled"
    else
        log "controller not installed"
    fi
fi

# ---------------------------------------------------------------------------
step "3/9  The namespace"
# ---------------------------------------------------------------------------
if kubectl delete namespace "$NAMESPACE" --timeout=5m 2>/dev/null; then
    ok "namespace gone"
else
    log "no namespace"
fi

# ---------------------------------------------------------------------------
step "4/9  The Lambda stack, and the log group SAM does not own"
# ---------------------------------------------------------------------------
if stack_exists "$SAM_STACK"; then
    aws cloudformation delete-stack --stack-name "$SAM_STACK"
    aws cloudformation wait stack-delete-complete --stack-name "$SAM_STACK" 2>/dev/null || true
    ok "$SAM_STACK deleted"
else
    log "no $SAM_STACK"
fi

# template.yaml declares the log group, so the stack delete above takes it and
# this is a no-op. It stays because a function deployed before that declaration
# existed owns a group CloudFormation never knew about, with retention set to
# "Never expire". $0.03/GB/month: pennies, and permanent.
if aws logs delete-log-group \
        --log-group-name /aws/lambda/booking-event-handler 2>/dev/null; then
    ok "lambda log group deleted"
fi

# ---------------------------------------------------------------------------
step "5/9  The database, BEFORE the cluster"
# ---------------------------------------------------------------------------
if stack_exists "$DATA_STACK"; then
    aws cloudformation delete-stack --stack-name "$DATA_STACK"
    log "waiting (RDS deletion takes 3-5 minutes)..."
    aws cloudformation wait stack-delete-complete --stack-name "$DATA_STACK" 2>/dev/null || true
    ok "$DATA_STACK deleted"
else
    log "no $DATA_STACK"
fi

# ---------------------------------------------------------------------------
step "6/9  The cluster — the ~15 minute step"
# ---------------------------------------------------------------------------
if command -v eksctl >/dev/null 2>&1 && eksctl get cluster --name "$CLUSTER_NAME" >/dev/null 2>&1; then
    # --disable-nodegroup-eviction: nodes are drained, and a PDB that cannot be
    # satisfied (minAvailable 1 with one pod left) would otherwise stall the
    # delete until it times out. The PDB has done its job by this point.
    eksctl delete cluster --name "$CLUSTER_NAME" --wait --disable-nodegroup-eviction \
        || warn "eksctl reported a problem — the sweep below will say what is left"
    ok "cluster deleted"
else
    log "no cluster"
fi

# ---------------------------------------------------------------------------
step "7/9  SAM's own managed bucket"
# ---------------------------------------------------------------------------
# `sam deploy --resolve-s3` creates a stack nobody remembers: a versioned
# bucket holding every build artefact. It is cents per month and it is not
# deleted by anything above, so it is the classic leftover.
SAM_MANAGED=aws-sam-cli-managed-default
if stack_exists "$SAM_MANAGED"; then
    bucket=$(stack_output "$SAM_MANAGED" SourceBucket)
    if [ -n "$bucket" ]; then
        log "emptying s3://$bucket (all versions)..."
        aws s3api delete-objects --bucket "$bucket" \
            --delete "$(aws s3api list-object-versions --bucket "$bucket" \
                --output json --query '{Objects: Versions[].{Key:Key,VersionId:VersionId}}' \
                2>/dev/null)" >/dev/null 2>&1 || true
        aws s3api delete-objects --bucket "$bucket" \
            --delete "$(aws s3api list-object-versions --bucket "$bucket" \
                --output json --query '{Objects: DeleteMarkers[].{Key:Key,VersionId:VersionId}}' \
                2>/dev/null)" >/dev/null 2>&1 || true
    fi
    aws cloudformation delete-stack --stack-name "$SAM_MANAGED"
    aws cloudformation wait stack-delete-complete --stack-name "$SAM_MANAGED" 2>/dev/null || true
    ok "$SAM_MANAGED deleted"
else
    log "no $SAM_MANAGED stack"
fi

# ---------------------------------------------------------------------------
step "8/9  Foundation and the leftover IAM"
# ---------------------------------------------------------------------------
if [ "$KEEP_FOUNDATION" = 1 ]; then
    log "keeping $FOUNDATION_STACK (--keep-foundation)"
elif stack_exists "$FOUNDATION_STACK"; then
    # A repository with images in it cannot be deleted unless the template said
    # EmptyOnDelete, which foundation.yaml does. This is the belt to that
    # braces: an ECR repo created before that setting existed would block here.
    ECR_NAME=flight-ops-service
    if aws ecr delete-repository \
            --repository-name "$ECR_NAME" --force >/dev/null 2>&1; then
        log "emptied and deleted the ECR repository"
    fi
    aws cloudformation delete-stack --stack-name "$FOUNDATION_STACK"
    aws cloudformation wait stack-delete-complete --stack-name "$FOUNDATION_STACK" 2>/dev/null || true
    ok "$FOUNDATION_STACK deleted"
else
    log "no $FOUNDATION_STACK"
fi

# The LB controller policy is created by up.sh with the CLI, not by a stack, so
# nothing deletes it. It costs nothing and is harmless; it is removed anyway,
# because "nothing with this project's name is left" is easier to verify than
# "nothing except these four known-harmless things".
LBC_POLICY_ARN="arn:aws:iam::${ACCOUNT_ID}:policy/AWSLoadBalancerControllerIAMPolicy"
if aws iam get-policy --policy-arn "$LBC_POLICY_ARN" >/dev/null 2>&1; then
    for v in $(aws iam list-policy-versions --policy-arn "$LBC_POLICY_ARN" \
            --query 'Versions[?!IsDefaultVersion].VersionId' --output text 2>/dev/null); do
        aws iam delete-policy-version --policy-arn "$LBC_POLICY_ARN" --version-id "$v" >/dev/null 2>&1 || true
    done
    if aws iam delete-policy --policy-arn "$LBC_POLICY_ARN" >/dev/null 2>&1; then
        ok "AWSLoadBalancerControllerIAMPolicy deleted"
    else
        warn "could not delete AWSLoadBalancerControllerIAMPolicy (a role may still be attached)"
    fi
fi

# ---------------------------------------------------------------------------
step "9/9  The sweep — this is the part that actually matters"
# ---------------------------------------------------------------------------
# Every delete above can quietly half-succeed. This looks for the things that
# bill, by name, and exits non-zero if any of them is still there. A clean run
# of this section is the only honest answer to "is it definitely gone?".
FAILURES=0
check() {  # check <label> <expected-empty-output>
    local label="$1" found="$2"
    if [ -z "$found" ] || [ "$found" = "None" ]; then
        printf '  %sPASS%s  %s\n' "$C_GREEN" "$C_RESET" "$label"
    else
        printf '  %sFAIL%s  %s: %s\n' "$C_RED" "$C_RESET" "$label" "$(echo "$found" | tr '\n\t' '  ')"
        FAILURES=$((FAILURES + 1))
    fi
}

check "load balancers (v2)" "$(aws elbv2 describe-load-balancers \
    --query "LoadBalancers[?contains(LoadBalancerName, 'k8s-flightop')].LoadBalancerName" --output text 2>/dev/null)"
check "load balancers (classic)" "$(aws elb describe-load-balancers \
    --query "LoadBalancerDescriptions[?contains(LoadBalancerName, 'flight')].LoadBalancerName" --output text 2>/dev/null)"
check "EKS clusters" "$(aws eks list-clusters --query "clusters[?@=='$CLUSTER_NAME']" --output text 2>/dev/null)"
check "EC2 instances" "$(aws ec2 describe-instances \
    --filters "Name=tag:Project,Values=flight-ops" "Name=instance-state-name,Values=pending,running,stopping,stopped" \
    --query 'Reservations[].Instances[].InstanceId' --output text 2>/dev/null)"
check "NAT gateways" "$(aws ec2 describe-nat-gateways \
    --filter "Name=tag:Project,Values=flight-ops" \
    --query "NatGateways[?State!='deleted'].NatGatewayId" --output text 2>/dev/null)"
# Unattached EBS volumes and unassociated Elastic IPs are the two that survive a
# botched cluster delete and bill in silence: $0.0912/GB/month and $0.005/hr.
check "EBS volumes" "$(aws ec2 describe-volumes \
    --filters "Name=status,Values=available,in-use" "Name=tag:Project,Values=flight-ops" \
    --query 'Volumes[].VolumeId' --output text 2>/dev/null)"
check "unassociated Elastic IPs" "$(aws ec2 describe-addresses \
    --query 'Addresses[?AssociationId==null].PublicIp' --output text 2>/dev/null)"
check "RDS instances" "$(aws rds describe-db-instances \
    --query "DBInstances[?contains(DBInstanceIdentifier, 'flight-ops')].DBInstanceIdentifier" --output text 2>/dev/null)"
check "RDS snapshots" "$(aws rds describe-db-snapshots --snapshot-type manual \
    --query "DBSnapshots[?contains(DBSnapshotIdentifier, 'flight-ops')].DBSnapshotIdentifier" --output text 2>/dev/null)"
check "CloudFormation stacks" "$(aws cloudformation list-stacks \
    --stack-status-filter CREATE_COMPLETE UPDATE_COMPLETE ROLLBACK_COMPLETE UPDATE_ROLLBACK_COMPLETE DELETE_FAILED \
    --query "StackSummaries[?contains(StackName, 'flight-ops')].StackName" --output text 2>/dev/null)"
check "log groups" "$(aws logs describe-log-groups \
    --query "logGroups[?contains(logGroupName, 'flight-ops') || contains(logGroupName, 'booking-event')].logGroupName" \
    --output text 2>/dev/null)"
check "Secrets Manager secrets" "$(aws secretsmanager list-secrets \
    --query "SecretList[?contains(Name, 'flight-ops')].Name" --output text 2>/dev/null)"
if [ "$KEEP_FOUNDATION" = 0 ]; then
    check "ECR repositories" "$(aws ecr describe-repositories \
        --query "repositories[?contains(repositoryName, 'flight-ops')].repositoryName" --output text 2>/dev/null)"
fi
# The catch-all. The specific checks above know what to look for; this one
# finds anything tagged Project=flight-ops that nobody thought to check.
check "anything tagged Project=flight-ops" "$(aws resourcegroupstaggingapi get-resources \
    --tag-filters Key=Project,Values=flight-ops \
    --query 'ResourceTagMappingList[].ResourceARN' --output text 2>/dev/null)"

echo
if [ "$FAILURES" -eq 0 ]; then
    if [ -f "$STATE_FILE" ]; then
        archive="${STATE_FILE}.$(aws sts get-caller-identity --query Account --output text 2>/dev/null || echo old).done"
        mv "$STATE_FILE" "$archive" 2>/dev/null || true
        log "state archived to $archive"
    fi
    ok "everything is gone. Billing for this project stops accruing now."
    cat <<'DONE'

  Two things this script cannot prove:

    - Cost Explorer lags 8-24 hours. Check tomorrow with cost-check.sh; the
      daily line should fall to zero, not to "small".
    - Data already transferred, and storage already consumed this month, is
      still billed at the end of the month. "Deleted" is not "refunded".

DONE
    exit 0
fi

warn "$FAILURES check(s) failed — something is still billing."
cat <<'FAILED'

  Re-run this script: most failures are ordering, and a second pass succeeds
  once the thing that was blocking has finished deleting. If a check fails
  twice, delete it by hand in the console — the ARN is printed above — and
  look for a dependency: a security group referenced by another group, a
  network interface still attached, a stack in DELETE_FAILED with a reason
  in its events.

FAILED
exit 1
