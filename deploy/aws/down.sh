#!/usr/bin/env bash
# Deletes everything up.sh created, in the reverse order, and then proves it.
#
#   ./deploy/aws/down.sh                     # everything this project created
#   ./deploy/aws/down.sh --keep-foundation   # leave ECR and the CI role
#   ./deploy/aws/down.sh --delete-sam-bucket # also remove SAM's SHARED bucket
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
DELETE_SAM_BUCKET=0
for arg in "$@"; do
    case "$arg" in
        --keep-foundation)  KEEP_FOUNDATION=1 ;;
        --delete-sam-bucket) DELETE_SAM_BUCKET=1 ;;
        *) printf 'down.sh: unknown option %s\n' "$arg" >&2; exit 2 ;;
    esac
done

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

  Two things are deliberately NOT deleted, because both are shared with the
  rest of the account rather than owned by this project:

    - the GitHub OIDC provider for token.actions.githubusercontent.com. One per
      account; other repositories may authenticate through it. Free.
      deploy/aws/foundation.yaml explains the reasoning.
    - the aws-sam-cli-managed-default bucket and stack, which every SAM project
      in this region shares. Pennies per month. Pass --delete-sam-bucket if
      nothing else in this account deploys with SAM.

PLAN
confirm "This deletes data permanently." "delete"

# ---------------------------------------------------------------------------
step "1/9  Ingress first, so the controller can delete its own load balancer"
# ---------------------------------------------------------------------------
# "kubectl said no" is two different facts and this step cannot afford to
# conflate them. `kubectl get ingress` exits non-zero both when the Ingress is
# absent and when the cluster is unreachable -- no kubeconfig context, an
# expired token, a VPN that is down -- and treating the second as the first
# skips the one step whose whole purpose is not orphaning a billed load
# balancer. So connectivity is established first, separately.
if ! kubectl cluster-info --request-timeout=15s >/dev/null 2>&1; then
    warn "kubectl cannot reach a cluster, so the Ingress cannot be deleted first."
    warn "If an ALB exists it will be ORPHANED by the cluster delete below and"
    warn "will keep billing at about \$0.62/day with nothing pointing at it."
    warn "Point kubectl at the cluster and re-run:"
    warn "  aws eks update-kubeconfig --name $CLUSTER_NAME --region $AWS_REGION"

    # up.sh recorded the hostname when it created the Ingress, so there is one
    # more place to look before giving up on finding the load balancer. This is
    # what the state file is for.
    if saved_host=$(state_get ALB_HOST); then
        saved_arn=$(aws elbv2 describe-load-balancers \
            --query "LoadBalancers[?DNSName=='$saved_host'].LoadBalancerArn" \
            --output text 2>/dev/null || true)
        if [ -n "$saved_arn" ] && [ "$saved_arn" != "None" ]; then
            warn "the ALB from the last up.sh run IS still there:"
            warn "  $saved_host"
            warn "  $saved_arn"
            warn "Delete it by hand before the cluster goes, or the sweep will fail:"
            warn "  aws elbv2 delete-load-balancer --load-balancer-arn $saved_arn"
        else
            log "the ALB recorded by the last up.sh run is already gone"
        fi
    fi
    confirm "Continue anyway, accepting that risk?" "continue"
elif kubectl get ingress flight-ops-ingress -n "$NAMESPACE" >/dev/null 2>&1; then
    alb_host=$(kubectl get ingress flight-ops-ingress -n "$NAMESPACE" \
        -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)
    kubectl delete ingress flight-ops-ingress -n "$NAMESPACE" --timeout=5m || true

    if [ -n "$alb_host" ]; then
        # Deleting the Ingress object returns immediately; the controller then
        # deletes the ALB asynchronously, which takes a minute or three. The
        # wait is here so the cluster delete two steps down does not race it.
        log "waiting for the load balancer to disappear from the AWS API (up to 10 minutes)..."
        alb_gone=0
        for _ in $(seq 1 60); do
            found=$(aws elbv2 describe-load-balancers \
                --query "LoadBalancers[?DNSName=='$alb_host'].LoadBalancerArn" \
                --output text 2>/dev/null || true)
            if [ -z "$found" ] || [ "$found" = "None" ]; then alb_gone=1; break; fi
            sleep 10
        done
        if [ "$alb_gone" = 1 ]; then
            ok "ingress and ALB gone"
        else
            # Not fatal here, and not silently "ok" either. The sweep at the end
            # checks for load balancers by name and will fail the run if this
            # one is still there, which is the check that matters.
            warn "the ALB behind $alb_host was still present after 10 minutes"
            warn "continuing; the sweep in step 9 decides whether this run passed"
        fi
    else
        ok "ingress deleted (it had no load balancer address)"
    fi
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
step "7/9  SAM's own managed bucket — SHARED, so opt-in only"
# ---------------------------------------------------------------------------
# `sam deploy --resolve-s3` creates a stack nobody remembers: aws-sam-cli-
# managed-default, a versioned bucket holding build artefacts. It is cents per
# month and nothing above deletes it, so it looks exactly like the classic
# leftover this script exists to catch.
#
# It is not. THE NAME IS FIXED PER ACCOUNT AND REGION. Every SAM project
# deployed with --resolve-s3 in this account shares that one bucket, and this
# script has no way to tell which objects belong to this project. Deleting it
# because we happened to deploy into it destroys another project's artefacts
# and breaks the next `sam deploy` somebody else runs, to save about $0.05 a
# month. That is the same class of mistake as taking the account's shared OIDC
# provider with the foundation stack, and it gets the same answer: leave it,
# say so, and offer a flag for the operator who knows the account is theirs
# alone.
SAM_MANAGED=aws-sam-cli-managed-default
if ! stack_exists "$SAM_MANAGED"; then
    log "no $SAM_MANAGED stack"
elif [ "$DELETE_SAM_BUCKET" = 0 ]; then
    log "leaving $SAM_MANAGED alone — it is shared by every SAM project in this"
    log "account and region. Pennies per month. Remove it with --delete-sam-bucket"
    log "once you are sure nothing else deploys with SAM here."
else
    bucket=$(stack_output "$SAM_MANAGED" SourceBucket)
    if [ -n "$bucket" ]; then
        # Paginated, deliberately. list-object-versions returns at most 1000
        # keys per call, so the single-shot version of this left everything
        # past the first page in place -- and then the stack delete failed on a
        # non-empty bucket while the script printed "deleted".
        log "emptying s3://$bucket (all versions, paginated)..."
        emptied=0
        while :; do
            payload=$(aws s3api list-object-versions --bucket "$bucket" --max-items 1000 \
                --output json \
                --query '{Objects: [Versions, DeleteMarkers][].{Key:Key,VersionId:VersionId}}' \
                2>/dev/null || true)
            case "$payload" in
                ''|*'"Objects": null'*|*'"Objects": []'*) break ;;
            esac
            aws s3api delete-objects --bucket "$bucket" --delete "$payload" >/dev/null 2>&1 || break
            emptied=$((emptied + 1))
            [ "$emptied" -ge 100 ] && { warn "stopped after 100 pages — empty s3://$bucket by hand"; break; }
        done
    fi
    aws cloudformation delete-stack --stack-name "$SAM_MANAGED"
    if aws cloudformation wait stack-delete-complete --stack-name "$SAM_MANAGED" 2>/dev/null; then
        ok "$SAM_MANAGED deleted"
    else
        warn "$SAM_MANAGED did not finish deleting — usually a bucket that is still not empty"
    fi
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

# Every check below goes through this rather than calling `aws` directly, and
# the reason is the worst bug this script could have.
#
# `check` treats empty output as PASS. A failed AWS call also produces empty
# output on stdout. Write the checks as `$(aws ... 2>/dev/null)` and an expired
# token, a throttle, a missing permission or a typo'd query makes every single
# check print PASS, and the script exits 0 with "everything is gone. Billing for
# this project stops accruing now." while a cluster, a NAT gateway and an RDS
# instance carry on billing. A teardown verifier that reports success when it
# cannot see the account is worse than no verifier: it is the one output here
# anybody actually relies on.
#
# So a non-zero exit becomes LOUD OUTPUT instead of no output, which `check`
# then reports as a FAIL, which exits the script non-zero. Fail closed.
q() {  # q <aws args...> -- prints the query output, or a failure sentinel
    local out status
    out=$(aws "$@" 2>&1); status=$?
    if [ "$status" -ne 0 ]; then
        printf 'QUERY FAILED (aws %s %s): %s' \
            "${1:-}" "${2:-}" "$(printf '%s' "$out" | tr '\n' ' ' | cut -c1-160)"
        return 0
    fi
    printf '%s' "$out"
}

check "load balancers (v2)" "$(q elbv2 describe-load-balancers \
    --query "LoadBalancers[?contains(LoadBalancerName, 'k8s-flightop')].LoadBalancerName" --output text)"
check "load balancers (classic)" "$(q elb describe-load-balancers \
    --query "LoadBalancerDescriptions[?contains(LoadBalancerName, 'flight')].LoadBalancerName" --output text)"
check "EKS clusters" "$(q eks list-clusters --query "clusters[?@=='$CLUSTER_NAME']" --output text)"
check "EC2 instances" "$(q ec2 describe-instances \
    --filters "Name=tag:Project,Values=flight-ops" "Name=instance-state-name,Values=pending,running,stopping,stopped" \
    --query 'Reservations[].Instances[].InstanceId' --output text)"
check "NAT gateways" "$(q ec2 describe-nat-gateways \
    --filter "Name=tag:Project,Values=flight-ops" \
    --query "NatGateways[?State!='deleted'].NatGatewayId" --output text)"
# Unattached EBS volumes and unassociated Elastic IPs are the two that survive a
# botched cluster delete and bill in silence: $0.0912/GB/month and $0.005/hr.
check "EBS volumes" "$(q ec2 describe-volumes \
    --filters "Name=status,Values=available,in-use" "Name=tag:Project,Values=flight-ops" \
    --query 'Volumes[].VolumeId' --output text)"
# Tag-filtered, like everything else here. An unfiltered describe-addresses
# returns every unassociated Elastic IP IN THE ACCOUNT, so a stray address
# belonging to somebody else's stack would fail this teardown and send the
# operator hunting for a resource this project never created. The NAT gateway's
# address carries the cluster tags, because eksctl puts them on its stack and
# CloudFormation propagates stack tags to the EIP.
check "unassociated Elastic IPs" "$(q ec2 describe-addresses \
    --filters "Name=tag:Project,Values=flight-ops" \
    --query 'Addresses[?AssociationId==null].PublicIp' --output text)"
check "RDS instances" "$(q rds describe-db-instances \
    --query "DBInstances[?contains(DBInstanceIdentifier, 'flight-ops')].DBInstanceIdentifier" --output text)"
check "RDS snapshots" "$(q rds describe-db-snapshots --snapshot-type manual \
    --query "DBSnapshots[?contains(DBSnapshotIdentifier, 'flight-ops')].DBSnapshotIdentifier" --output text)"
check "CloudFormation stacks" "$(q cloudformation list-stacks \
    --stack-status-filter CREATE_COMPLETE UPDATE_COMPLETE ROLLBACK_COMPLETE UPDATE_ROLLBACK_COMPLETE DELETE_FAILED \
    --query "StackSummaries[?contains(StackName, 'flight-ops')].StackName" --output text)"
check "log groups" "$(q logs describe-log-groups \
    --query "logGroups[?contains(logGroupName, 'flight-ops') || contains(logGroupName, 'booking-event')].logGroupName" \
    --output text)"
check "Secrets Manager secrets" "$(q secretsmanager list-secrets \
    --query "SecretList[?contains(Name, 'flight-ops')].Name" --output text)"
if [ "$KEEP_FOUNDATION" = 0 ]; then
    check "ECR repositories" "$(q ecr describe-repositories \
        --query "repositories[?contains(repositoryName, 'flight-ops')].repositoryName" --output text)"
fi
# The catch-all. The specific checks above know what to look for; this one
# finds anything tagged Project=flight-ops that nobody thought to check.
#
# Its blind spot is the mirror image of theirs: it only sees what carries the
# tag. The ALB the load balancer controller provisions is tagged by the
# controller, not by this project, which is why "load balancers (v2)" above
# matches on the k8s-flightop name prefix instead. A resource that is neither
# tagged nor named after this project is invisible to both, and the only
# defence against that is the Cost Explorer check the next day.
check "anything tagged Project=flight-ops" "$(q resourcegroupstaggingapi get-resources \
    --tag-filters Key=Project,Values=flight-ops \
    --query 'ResourceTagMappingList[].ResourceARN' --output text)"

# Advisory, not a gate: unassociated addresses anywhere in the account. These
# bill at $0.005/hour each whether or not this project made them, and a
# teardown run is the moment somebody is actually looking.
stray_ips=$(aws ec2 describe-addresses \
    --query 'Addresses[?AssociationId==null].PublicIp' --output text 2>/dev/null || true)
if [ -n "$stray_ips" ] && [ "$stray_ips" != "None" ]; then
    warn "unassociated Elastic IPs elsewhere in this account (not this project, not failing): $stray_ips"
fi

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
