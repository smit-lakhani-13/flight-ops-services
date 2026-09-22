#!/usr/bin/env bash
# Shared helpers for the deploy scripts. Sourced, never executed.

# The region is pinned and exported, so the caller's profile never decides it.
# `aws configure get region` can differ between the shell that created a stack
# and the shell that deletes it. The teardown then looks in an empty region
# and reports a clean sweep. Pinning it here makes every call agree.
export AWS_REGION=ap-south-1
export AWS_DEFAULT_REGION=$AWS_REGION

# What a run created, so a later run or down.sh can find it. Ignored by git:
# it holds account ids and endpoints.
STATE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/.state"
STATE_FILE="$STATE_DIR/flight-ops.env"

# Names, in one place. up.sh creates them and down.sh deletes them, and a name
# that differs between the two is a resource no one finds again.
# shellcheck disable=SC2034  # read by the scripts that source this file
CLUSTER_NAME=flight-ops-cluster
# shellcheck disable=SC2034
NAMESPACE=flight-ops
# shellcheck disable=SC2034
FOUNDATION_STACK=flight-ops-foundation
# shellcheck disable=SC2034
DATA_STACK=flight-ops-data
# shellcheck disable=SC2034
SAM_STACK=flight-ops-lambda

if [ -t 1 ] && [ -z "${NO_COLOUR:-}" ]; then
    C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_RED=$'\033[31m'
    C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_BLUE=$'\033[34m'
else
    C_RESET=''; C_BOLD=''; C_RED=''; C_GREEN=''; C_YELLOW=''; C_BLUE=''
fi

step()  { printf '\n%s==> %s%s\n' "$C_BOLD$C_BLUE" "$*" "$C_RESET"; }
log()   { printf '    %s\n' "$*"; }
ok()    { printf '    %s✓ %s%s\n' "$C_GREEN" "$*" "$C_RESET"; }
warn()  { printf '    %s! %s%s\n' "$C_YELLOW" "$*" "$C_RESET" >&2; }
die()   { printf '\n%sERROR: %s%s\n' "$C_RED$C_BOLD" "$*" "$C_RESET" >&2; exit 1; }

require_tool() {
    local missing=()
    for tool in "$@"; do
        command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
    done
    if [ ${#missing[@]} -gt 0 ]; then
        die "missing required tool(s): ${missing[*]}
    brew install eksctl kubernetes-cli helm aws-sam-cli awscli"
    fi
}

# Fails early and legibly, before the first real call fails with an opaque
# token error twenty seconds in.
require_credentials() {
    local identity
    identity=$(aws sts get-caller-identity --output text --query 'Account' 2>&1) || die \
        "AWS credentials are not usable: $identity
    Refresh them (aws configure, or aws configure sso) and try again."
    printf '%s' "$identity"
}

state_init() {
    mkdir -p "$STATE_DIR"
    [ -f "$STATE_FILE" ] || : > "$STATE_FILE"
}

# Idempotent: re-running a step replaces its value instead of appending a
# second one.
state_set() {
    local key=$1 value=$2
    state_init
    if grep -q "^${key}=" "$STATE_FILE" 2>/dev/null; then
        # A temporary file, not sed -i: BSD sed (macOS) and GNU sed disagree
        # about -i's argument. `|| true` because grep exits 1 when it selects
        # no lines, which happens when this key is the only one in the file.
        # Under up.sh's `set -e` that would end the run on the re-run after an
        # early failure.
        grep -v "^${key}=" "$STATE_FILE" > "$STATE_FILE.tmp" || true
        mv "$STATE_FILE.tmp" "$STATE_FILE"
    fi
    printf '%s=%s\n' "$key" "$value" >> "$STATE_FILE"
}

# Prints the value for a key, or returns 1 if the file or the key is absent,
# so a caller can tell "not set" from "set to nothing".
state_get() {
    local key=$1 line
    [ -f "$STATE_FILE" ] || return 1
    line=$(grep "^${key}=" "$STATE_FILE" | tail -1)
    [ -n "$line" ] || return 1
    printf '%s' "${line#*=}"
}

# A typed word, not a keystroke. These prompts guard things that cost money or
# delete data, and "press y to continue" gets answered without reading.
confirm() {
    local prompt=$1 expected=${2:-yes} answer
    if [ -n "${ASSUME_YES:-}" ]; then
        warn "ASSUME_YES set — continuing without asking: $prompt"
        return 0
    fi
    printf '\n%s%s%s\n' "$C_BOLD" "$prompt" "$C_RESET"
    printf 'Type %s to continue: ' "$expected"
    read -r answer
    [ "$answer" = "$expected" ] || die "cancelled (you typed '$answer')"
}

stack_exists() {
    aws cloudformation describe-stacks --stack-name "$1" >/dev/null 2>&1
}

# Empty for a stack that does not exist, otherwise e.g. CREATE_COMPLETE. Any
# other error stops the run: taking a throttled call for "no stack" would
# redeploy the data stack with a new database password.
stack_status() {
    local out
    if out=$(aws cloudformation describe-stacks --stack-name "$1" \
            --query 'Stacks[0].StackStatus' --output text 2>&1); then
        printf '%s' "$out"
    else
        case "$out" in
            *'does not exist'*) ;;
            *) die "could not read the status of stack $1: $out" ;;
        esac
    fi
}

# Returns 0 for a stack that finished creating or updating, and 1 for one that
# does not exist, so the caller creates it. Any other state stops the run:
# ROLLBACK_COMPLETE after a failed create has no outputs and cannot be
# updated, and a stack still in progress has no outputs yet.
stack_ready() {
    local stack=$1 status
    status=$(stack_status "$stack") || exit 1
    case "$status" in
        "") return 1 ;;
        CREATE_COMPLETE|UPDATE_COMPLETE|UPDATE_ROLLBACK_COMPLETE) return 0 ;;
        *_IN_PROGRESS) die "stack $stack is $status. Wait for it to finish, then re-run." ;;
        *) die "stack $stack is $status and cannot be used. Delete it and re-run:
    aws cloudformation delete-stack --stack-name $stack
    aws cloudformation wait stack-delete-complete --stack-name $stack" ;;
    esac
}

# Empty when the stack or the output is missing. The CLI prints the word None
# for an empty --output text query, and a caller that tests for an empty string
# would otherwise take "None" as a value.
stack_output() {
    aws cloudformation describe-stacks --stack-name "$1" \
        --query "Stacks[0].Outputs[?OutputKey=='$2'].OutputValue" --output text 2>/dev/null \
        | sed '/^None$/d'
}

# eksctl writes its own CloudFormation stack; its outputs are the only reliable
# source for the VPC and subnet ids the data stack needs.
eksctl_stack_output() {
    stack_output "eksctl-${CLUSTER_NAME}-cluster" "$1"
}

# ---------------------------------------------------------------------------
# up.sh's checks. They live here so selftest.sh can run each one against stubs.
# ---------------------------------------------------------------------------

# The enforcer rule in lambda/pom.xml accepts JDK 21 only, so a wrong
# JAVA_HOME is caught in the preflight, before anything bills.
require_jdk21() {
    local mvnw=$1 major
    major=$("$mvnw" -v 2>/dev/null | sed -n 's/^Java version: \([0-9]*\).*/\1/p' || true)
    [ "$major" = 21 ] || die "the Lambda build needs JDK 21; the Maven wrapper sees '${major:-no JDK}'.
    brew install openjdk@21, then point JAVA_HOME at it."
}

# stack_output prints nothing for a missing output, and render-aws.sh renders
# whatever string it is given into the ConfigMap. So anything that is not a
# PostgreSQL URL stops here.
require_jdbc_url() {
    case "$1" in
        jdbc:postgresql://*) ;;
        *) die "the data stack has no usable JdbcUrl output (got '${1:-nothing}')" ;;
    esac
}

# grant_namespace_access PRINCIPAL POLICY NAMESPACE: gives the principal the
# access policy on one namespace, creating its access entry first if needed.
# The associate call's exit code cannot tell "already associated" from
# "refused", so the association is read back and checked.
grant_namespace_access() {
    local principal=$1 policy=$2 namespace=$3 associated
    if aws eks describe-access-entry --cluster-name "$CLUSTER_NAME" \
            --principal-arn "$principal" >/dev/null 2>&1; then
        log "access entry already exists"
    else
        aws eks create-access-entry --cluster-name "$CLUSTER_NAME" \
            --principal-arn "$principal" --type STANDARD >/dev/null \
            || die "could not create an access entry for $principal"
    fi
    aws eks associate-access-policy \
        --cluster-name "$CLUSTER_NAME" \
        --principal-arn "$principal" \
        --policy-arn "$policy" \
        --access-scope "type=namespace,namespaces=$namespace" >/dev/null 2>&1 || true
    associated=$(aws eks list-associated-access-policies \
        --cluster-name "$CLUSTER_NAME" \
        --principal-arn "$principal" \
        --query "associatedAccessPolicies[?policyArn=='$policy' && accessScope.type=='namespace' && contains(accessScope.namespaces, '$namespace')].policyArn" \
        --output text 2>/dev/null || true)
    [ "$associated" = "$policy" ] || die "$principal has no ${policy##*/} scoped to namespace/$namespace. See:
    aws eks list-associated-access-policies --cluster-name $CLUSTER_NAME --principal-arn $principal"
}

# kubectl wait exits at once with NotFound for an object that does not exist
# yet, and CI creates the Deployment some minutes after the operator types
# "done". So this waits up to 30 minutes for it to appear, then up to 20 for
# it to become available.
wait_for_deployment() {
    local seen=0
    log "waiting for CI to create the deployment (up to 30 min)..."
    for _ in $(seq 1 180); do
        if kubectl get deployment/flight-ops -n "$NAMESPACE" >/dev/null 2>&1; then
            seen=1
            break
        fi
        sleep 10
    done
    [ "$seen" = 1 ] || die "no deployment/flight-ops in namespace $NAMESPACE after 30 minutes.
    Check the workflow run in GitHub Actions. The deploy job is skipped unless
    DEPLOY_ENABLED is true and the run is on main."

    log "waiting for the deployment to become available (up to 20 min)..."
    kubectl wait --for=condition=available deployment/flight-ops \
        -n "$NAMESPACE" --timeout=20m \
        || die "the deployment did not become available. Diagnose with:
    kubectl get pods -n $NAMESPACE -o wide
    kubectl logs -n $NAMESPACE -l app=flight-ops --tail=100 --all-containers
    kubectl get events -n $NAMESPACE --sort-by=.lastTimestamp | tail -30"
}
