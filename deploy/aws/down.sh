#!/usr/bin/env bash
# Deletes everything up.sh created, in the reverse order, and then proves it.
#
#   ./deploy/aws/down.sh                     # everything this project created
#   ./deploy/aws/down.sh --keep-foundation   # leave ECR and the CI role
#   ./deploy/aws/down.sh --delete-sam-bucket # also remove SAM's SHARED bucket
#
# Order matters in two places:
#
#   1. The Ingress goes first. Deleting the namespace with an Ingress still in
#      it removes the Kubernetes object while the controller, also being
#      deleted, never gets to delete the ALB. The load balancer survives with
#      nothing in the cluster pointing at it, and bills until someone finds it.
#   2. The data stack goes before the cluster. Its security group lives in
#      eksctl's VPC, and a security group with a rule referencing it blocks the
#      VPC delete. eksctl then fails after 20 minutes with a message about a
#      dependency it cannot name.
#
# Every delete is best-effort, and a resource that is already gone is not an
# error. The sweep at the end decides whether the run succeeded.
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
# `|| exit 1`: die inside $(...) ends only the subshell, and this script runs
# without -e. Without it, expired credentials reach every delete call.
ACCOUNT_ID=$(require_credentials) || exit 1

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

  Two things are NOT deleted, because both are shared with the rest of the
  account instead of owned by this project:

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
# kubectl and helm use a kubeconfig written for this cluster alone, never the
# caller's current context. That context may point at another cluster, and
# steps 1-3 would then delete an Ingress, a controller and a namespace there.
KUBECONFIG=$(mktemp)
export KUBECONFIG
trap 'rm -f "$KUBECONFIG"' EXIT

# `kubectl get ingress` exits non-zero both when the Ingress is absent and when
# the cluster is unreachable (an expired token, a VPN that is down). Treating
# the second as the first would skip the step that stops a billed load
# balancer being orphaned, so connectivity is checked first, separately.
cluster_reachable=0
if command -v kubectl >/dev/null 2>&1 \
        && aws eks update-kubeconfig --name "$CLUSTER_NAME" --region "$AWS_REGION" \
            --kubeconfig "$KUBECONFIG" >/dev/null 2>&1 \
        && kubectl cluster-info --request-timeout=15s >/dev/null 2>&1; then
    cluster_reachable=1
fi

if [ "$cluster_reachable" = 0 ]; then
    warn "cannot reach cluster $CLUSTER_NAME, so the Ingress cannot be deleted first."
    warn "If the cluster exists and has an ALB, the cluster delete below will ORPHAN it"
    warn "and it will keep billing at about \$0.62/day with nothing pointing at it."
    warn "Check that this works from this shell, then re-run:"
    warn "  aws eks update-kubeconfig --name $CLUSTER_NAME --region $AWS_REGION"
    warn "If the cluster is already gone, continuing is safe."

    # up.sh recorded the hostname when it created the Ingress, which gives one
    # more place to look for the load balancer.
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
            # Not fatal here, and not "ok" either. The sweep checks for load
            # balancers by name and fails the run if this one is still there.
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
# nothing able to delete the ALB.
if [ "$cluster_reachable" = 0 ]; then
    log "skipped: the cluster is not reachable"
elif command -v helm >/dev/null 2>&1; then
    if ! helm status aws-load-balancer-controller -n kube-system >/dev/null 2>&1; then
        log "controller not installed"
    elif helm uninstall aws-load-balancer-controller -n kube-system --wait; then
        ok "controller uninstalled"
    else
        warn "could not uninstall the controller; the cluster delete takes it anyway"
    fi
fi

# ---------------------------------------------------------------------------
step "3/9  The namespace"
# ---------------------------------------------------------------------------
if [ "$cluster_reachable" = 0 ]; then
    log "skipped: the cluster is not reachable"
elif ! kubectl get namespace "$NAMESPACE" >/dev/null 2>&1; then
    log "no namespace"
elif kubectl delete namespace "$NAMESPACE" --timeout=5m; then
    ok "namespace gone"
else
    warn "namespace $NAMESPACE did not finish deleting; the cluster delete takes it anyway"
fi

# ---------------------------------------------------------------------------
step "4/9  The Lambda stack, and the log group SAM does not own"
# ---------------------------------------------------------------------------
if stack_exists "$SAM_STACK"; then
    aws cloudformation delete-stack --stack-name "$SAM_STACK"
    if aws cloudformation wait stack-delete-complete --stack-name "$SAM_STACK" 2>/dev/null; then
        ok "$SAM_STACK deleted"
    else
        warn "$SAM_STACK did not finish deleting; the sweep in step 9 will list it until it has gone"
    fi
else
    log "no $SAM_STACK"
fi

# lambda/template.yaml declares the log group, so the stack delete above normally
# takes it. This catches a group Lambda created by itself, outside the stack,
# which never expires.
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
    if aws cloudformation wait stack-delete-complete --stack-name "$DATA_STACK" 2>/dev/null; then
        ok "$DATA_STACK deleted"
    else
        warn "$DATA_STACK did not finish deleting; the VPC delete in step 6 may fail on its security group"
    fi
else
    log "no $DATA_STACK"
fi

# ---------------------------------------------------------------------------
step "6/9  The cluster — the ~15 minute step"
# ---------------------------------------------------------------------------
if ! command -v eksctl >/dev/null 2>&1; then
    warn "eksctl is not installed, so the cluster cannot be deleted from here"
elif eksctl get cluster --name "$CLUSTER_NAME" >/dev/null 2>&1; then
    # --disable-nodegroup-eviction: the application namespace is already gone,
    # and this stops whatever PDBs remain in kube-system from stalling the
    # drain while every node is removed at once.
    if eksctl delete cluster --name "$CLUSTER_NAME" --wait --disable-nodegroup-eviction; then
        ok "cluster deleted"
    else
        warn "eksctl reported a problem — the sweep below will say what is left"
    fi
else
    log "no cluster"
fi

# ---------------------------------------------------------------------------
step "7/9  SAM's own managed bucket — SHARED, so opt-in only"
# ---------------------------------------------------------------------------
# `sam deploy --resolve-s3` creates the aws-sam-cli-managed-default stack, a
# versioned bucket of build artefacts. Nothing above deletes it, so it looks
# like a leftover. Its name is fixed per account and region, though: every SAM
# project deployed with --resolve-s3 here shares it, and this script cannot
# tell which objects are this project's. Deleting it would break the next
# `sam deploy` someone else runs. It gets the same answer as the shared OIDC
# provider: leave it, say so, and offer a flag to the operator who knows the
# account is theirs alone.
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
        # Paginated: list-object-versions returns at most 1000 keys per
        # call, and the stack delete fails on a bucket that is not empty.
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
    # foundation.yaml sets EmptyOnDelete, so the stack delete can remove a
    # repository with images in it. The forced delete covers a repository
    # created without that setting, which would otherwise block the stack.
    ECR_NAME=flight-ops-service
    if aws ecr delete-repository \
            --repository-name "$ECR_NAME" --force >/dev/null 2>&1; then
        log "emptied and deleted the ECR repository"
    fi
    aws cloudformation delete-stack --stack-name "$FOUNDATION_STACK"
    if aws cloudformation wait stack-delete-complete --stack-name "$FOUNDATION_STACK" 2>/dev/null; then
        ok "$FOUNDATION_STACK deleted"
    else
        warn "$FOUNDATION_STACK did not finish deleting; the sweep in step 9 will list it until it has gone"
    fi
else
    log "no $FOUNDATION_STACK"
fi

# up.sh creates the LB controller policy with the CLI, outside any stack, so
# nothing else deletes it. It costs nothing, and it is removed anyway: "nothing
# of this project's is left" is easier to verify than a list of exceptions.
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
# Any delete above can half-succeed. This looks for the things that bill, by
# name and by tag, and exits non-zero if any of them is still there.
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

# Every check goes through q, never `aws` directly. `check` treats empty output
# as PASS, and a failed AWS call also prints nothing on stdout. An expired
# token, a throttle or a missing permission would then make every check pass
# while a cluster and a database carry on billing. q turns a non-zero exit into
# output, which `check` reports as a FAIL. Fail closed.
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
# failed cluster delete and keep billing.
check "EBS volumes" "$(q ec2 describe-volumes \
    --filters "Name=status,Values=available,in-use" "Name=tag:Project,Values=flight-ops" \
    --query 'Volumes[].VolumeId' --output text)"
# Tag-filtered. An unfiltered describe-addresses returns every unassociated
# Elastic IP in the account, and another stack's address would fail this
# teardown. The NAT gateway's address carries the cluster tags, because eksctl
# puts them on its stack and CloudFormation propagates stack tags to the EIP.
check "unassociated Elastic IPs" "$(q ec2 describe-addresses \
    --filters "Name=tag:Project,Values=flight-ops" \
    --query 'Addresses[?AssociationId==null].PublicIp' --output text)"
check "RDS instances" "$(q rds describe-db-instances \
    --query "DBInstances[?contains(DBInstanceIdentifier, 'flight-ops')].DBInstanceIdentifier" --output text)"
check "RDS snapshots" "$(q rds describe-db-snapshots --snapshot-type manual \
    --query "DBSnapshots[?contains(DBSnapshotIdentifier, 'flight-ops')].DBSnapshotIdentifier" --output text)"
# --keep-foundation keeps the foundation stack and the resources it owns, so
# neither this check nor the tag catch-all counts them.
stack_query="StackSummaries[?contains(StackName, 'flight-ops')].StackName"
if [ "$KEEP_FOUNDATION" = 1 ]; then
    stack_query="StackSummaries[?contains(StackName, 'flight-ops') && StackName!='$FOUNDATION_STACK'].StackName"
fi
# Every status but DELETE_COMPLETE, so a stack still deleting, or one whose
# delete or rollback failed, is listed, as steps 4 and 8 promise when the
# waiter gives up.
live_stack_statuses=(
    CREATE_IN_PROGRESS CREATE_FAILED CREATE_COMPLETE
    ROLLBACK_IN_PROGRESS ROLLBACK_FAILED ROLLBACK_COMPLETE
    DELETE_IN_PROGRESS DELETE_FAILED
    UPDATE_IN_PROGRESS UPDATE_COMPLETE_CLEANUP_IN_PROGRESS UPDATE_COMPLETE
    UPDATE_FAILED UPDATE_ROLLBACK_IN_PROGRESS UPDATE_ROLLBACK_FAILED
    UPDATE_ROLLBACK_COMPLETE_CLEANUP_IN_PROGRESS UPDATE_ROLLBACK_COMPLETE
    REVIEW_IN_PROGRESS IMPORT_IN_PROGRESS IMPORT_COMPLETE
    IMPORT_ROLLBACK_IN_PROGRESS IMPORT_ROLLBACK_FAILED IMPORT_ROLLBACK_COMPLETE
)
check "CloudFormation stacks" "$(q cloudformation list-stacks \
    --stack-status-filter "${live_stack_statuses[@]}" \
    --query "$stack_query" --output text)"
check "log groups" "$(q logs describe-log-groups \
    --query "logGroups[?contains(logGroupName, 'flight-ops') || contains(logGroupName, 'booking-event')].logGroupName" \
    --output text)"
check "Secrets Manager secrets" "$(q secretsmanager list-secrets \
    --query "SecretList[?contains(Name, 'flight-ops')].Name" --output text)"
if [ "$KEEP_FOUNDATION" = 0 ]; then
    check "ECR repositories" "$(q ecr describe-repositories \
        --query "repositories[?contains(repositoryName, 'flight-ops')].repositoryName" --output text)"
fi
# The catch-all: anything tagged Project=flight-ops that no check above names.
# The controller tags the ALB too, from the annotation in
# deploy/k8s/components/ingress/ingress.yaml, so a surviving ALB shows here as
# well as under "load balancers (v2)". A resource that is neither tagged nor
# named after this project is invisible to both. The Cost Explorer check the
# next day is the defence against that.
tagged=$(q resourcegroupstaggingapi get-resources \
    --tag-filters Key=Project,Values=flight-ops \
    --query 'ResourceTagMappingList[].ResourceARN' --output text)
# The query runs in ap-south-1, and AWS reports IAM resources from us-east-1,
# so no IAM role or OIDC provider reaches this list. IAM bills nothing, and the
# stacks check above still catches an eksctl stack that failed to delete. The
# patterns drop the foundation's resources wherever the tagging API lists them:
# the GitHub OIDC provider, which foundation.yaml retains, and with
# --keep-foundation the rest of the foundation. The ARNs are split once, so
# every pattern sees one ARN per line.
retained_arns=':oidc-provider/token\.actions\.githubusercontent\.com$'
if [ "$KEEP_FOUNDATION" = 1 ]; then
    retained_arns="$retained_arns|:stack/$FOUNDATION_STACK/|:repository/flight-ops-service\$|:role/github-actions-deploy\$"
    retained_arns="$retained_arns|:policy/flight-ops-service-sqs-publish\$|:budget/flight-ops-(monthly|daily)\$"
fi
tagged=$(printf '%s' "$tagged" | tr '\t' '\n' | grep -Ev "$retained_arns" | paste -sd' ' -)
check "anything tagged Project=flight-ops" "$tagged"

# Advisory only: unassociated addresses anywhere in the account bill
# whoever made them, and a teardown is when someone is looking.
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
    if [ "$KEEP_FOUNDATION" = 1 ]; then
        ok "everything but $FOUNDATION_STACK is gone. Billing for the rest stops accruing now."
    else
        ok "everything is gone. Billing for this project stops accruing now."
    fi
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
  once the thing that was blocking has finished deleting. A stack still in
  DELETE_IN_PROGRESS fails the stacks check until it has gone. If a check fails
  twice, delete it by hand in the console — the ARN is printed above — and
  look for a dependency: a security group referenced by another group, a
  network interface still attached, a stack in DELETE_FAILED with a reason
  in its events.

FAILED
exit 1
