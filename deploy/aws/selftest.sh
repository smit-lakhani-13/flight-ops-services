#!/usr/bin/env bash
# Runs down.sh, cost-check.sh, ecr-image-exists.sh and up.sh's checks in
# lib.sh against stub aws, kubectl, helm, eksctl, sleep and mvnw commands, and
# checks what each concludes. Nothing reaches AWS or a cluster, and it takes a
# few seconds.
#
#   deploy/aws/selftest.sh
#
# The scripts are copied into a temporary directory first, so their .state
# directory is a scratch one and a real run's state is never touched. Each stub
# reads its behaviour from STUB_* variables and appends every call to a log
# that the checks read back.
#
# CI's infra-lint job runs this. It needs bash (3.2 or later), python3 and
# openssl, which lib.sh encodes the Secret's values with.
set -uo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

mkdir -p "$tmp/deploy/aws" "$tmp/bin"
cp "$here/lib.sh" "$here/down.sh" "$here/cost-check.sh" "$here/ecr-image-exists.sh" \
    "$tmp/deploy/aws/"

# ---------------------------------------------------------------------------
# The stubs
# ---------------------------------------------------------------------------
cat > "$tmp/bin/aws" <<'STUB'
#!/usr/bin/env bash
printf 'aws %s\n' "$*" >> "$STUB_LOG"
service=${1:-}; op=${2:-}; waiter=${3:-}
stack='' query='' kubeconfig='' addon='' statuses=''
while [ $# -gt 0 ]; do
    case "$1" in
        --stack-status-filter)
            while [ $# -gt 1 ] && [ "${2#--}" = "$2" ]; do statuses="$statuses $2"; shift; done ;;
        --stack-name) stack=$2; shift ;;
        --query)      query=$2; shift ;;
        --kubeconfig) kubeconfig=$2; shift ;;
        --addon-name) addon=$2; shift ;;
    esac
    shift
done
missing() { echo "An error occurred (ValidationError): Stack with id $stack does not exist" >&2; exit 254; }
case "$service $op" in
    'sts get-caller-identity')
        [ "${STUB_STS_FAILS:-0}" = 0 ] || {
            echo 'An error occurred (ExpiredToken) when calling the GetCallerIdentity operation' >&2
            exit 254
        }
        echo 123456789012 ;;
    'eks update-kubeconfig')
        [ "${STUB_CLUSTER_UP:-0}" = 1 ] || { echo 'No cluster found' >&2; exit 254; }
        printf 'apiVersion: v1\nkind: Config\n' > "${kubeconfig:-$KUBECONFIG}" ;;
    'eks describe-access-entry')
        [ "${STUB_ACCESS_ENTRY:-0}" = 1 ] || { echo 'An error occurred (ResourceNotFoundException)' >&2; exit 254; } ;;
    'eks associate-access-policy')
        [ "${STUB_ASSOCIATE_FAILS:-0}" = 0 ] || { echo 'An error occurred (AccessDeniedException)' >&2; exit 254; } ;;
    # The real CLI applies the --query; the stub prints its result.
    'eks list-associated-access-policies') printf '%s\n' "${STUB_ASSOCIATED:-}" ;;
    'eks describe-cluster') echo "${STUB_ISSUER:-https://oidc.eks.ap-south-1.amazonaws.com/id/AB12}" ;;
    'eks describe-addon')
        [ "${STUB_ADDON_LOOKUP_FAILS:-0}" = 0 ] || { echo 'An error occurred (ThrottlingException) when calling the DescribeAddon operation' >&2; exit 254; }
        case " ${STUB_ADDONS_MISSING:-} " in
            *" $addon "*) echo "An error occurred (ResourceNotFoundException) when calling the DescribeAddon operation: No addon: $addon found" >&2; exit 254 ;;
        esac
        echo ACTIVE ;;
    'eks create-addon')
        [ "${STUB_ADDON_CREATE_FAILS:-0}" = 0 ] || { echo 'An error occurred (InvalidParameterException) when calling the CreateAddon operation' >&2; exit 254; } ;;
    'eks describe-nodegroup')
        case "${STUB_NODEGROUP:-}" in
            active)  echo ACTIVE ;;
            missing) echo 'An error occurred (ResourceNotFoundException) when calling the DescribeNodegroup operation' >&2; exit 254 ;;
            *)       echo 'An error occurred (ThrottlingException) when calling the DescribeNodegroup operation' >&2; exit 254 ;;
        esac ;;
    # STUB_EKS_WAIT_FAILS names the waiters that fail, such as nodegroup-active.
    'eks wait')
        case " ${STUB_EKS_WAIT_FAILS:-} " in
            *" $waiter "*) echo "Waiter $waiter failed: terminal failure state" >&2; exit 255 ;;
        esac ;;
    'iam list-open-id-connect-providers')
        [ "${STUB_OIDC_LIST_FAILS:-0}" = 0 ] || { echo 'An error occurred (Throttling): Rate exceeded' >&2; exit 254; }
        printf '%b' "${STUB_OIDC_PROVIDERS:-}" ;;
    'iam list-policies')
        [ "${STUB_POLICY_LIST_FAILS:-0}" = 0 ] || { echo 'An error occurred (Throttling): Rate exceeded' >&2; exit 254; }
        [ "${STUB_POLICY_LIST_WARNS:-0}" = 0 ] || echo 'PythonDeprecationWarning: Python 3.8 support ends soon' >&2
        printf '%b' "${STUB_POLICIES:-}" ;;
    'iam list-policy-versions') printf '%s' "${STUB_POLICY_VERSIONS:-}" ;;
    'ecr describe-images')
        case "${STUB_ECR:-}" in
            found)   echo '{"imageDetails": [{"imageTags": ["abc123"]}]}' ;;
            missing) echo 'An error occurred (ImageNotFoundException) when calling the DescribeImages operation' >&2; exit 254 ;;
            *)       echo 'An error occurred (AccessDeniedException) when calling the DescribeImages operation' >&2; exit 254 ;;
        esac ;;
    'cloudformation describe-stacks')
        [ "${STUB_DESCRIBE_FAILS:-0}" = 0 ] || { echo 'An error occurred (Throttling): Rate exceeded' >&2; exit 254; }
        case " ${STUB_STACKS:-} " in *" $stack "*) ;; *) missing ;; esac
        case "$query" in
            *StackStatus*) echo "${STUB_STACK_STATUS:-CREATE_COMPLETE}" ;;
            *Outputs*)     echo "${STUB_OUTPUT:-None}" ;;
        esac ;;
    'cloudformation delete-stack') ;;
    'cloudformation wait') [ "${STUB_WAIT_FAILS:-0}" = 0 ] || exit 255 ;;
    'cloudformation list-stacks')
        # Each entry is name or name:STATUS, CREATE_COMPLETE if none is given.
        # The status filter applies as AWS applies it: with none, every entry
        # is listed. The one part of the JMESPath query that matters here is
        # an exclusion.
        excluded=$(printf '%s' "$query" | sed -n "s/.*StackName!='\([^']*\)'.*/\1/p")
        for entry in ${STUB_LIST_STACKS:-}; do
            s=${entry%%:*} status=CREATE_COMPLETE
            [ "$s" = "$entry" ] || status=${entry#*:}
            [ -z "$statuses" ] || case " $statuses " in *" $status "*) ;; *) continue ;; esac
            [ "$s" = "$excluded" ] || printf '%s\t' "$s"
        done ;;
    'resourcegroupstaggingapi get-resources') printf '%b' "${STUB_TAGGED:-}" ;;
    'ce '*)
        [ "${STUB_CE_FAILS:-0}" = 0 ] || {
            echo 'An error occurred (AccessDeniedException): Cost Explorer is not enabled' >&2
            exit 254
        }
        echo '[]' ;;
    'budgets describe-budgets') echo 'flight-ops-daily  8  0.00  0.00' ;;
    'logs delete-log-group'|'ecr delete-repository')
        echo 'An error occurred (NotFound)' >&2; exit 254 ;;
    *) ;;  # every describe and list: nothing left
esac
exit 0
STUB

# kubectl and helm record which kubeconfig they were handed. The Deployment
# is NotFound for the first STUB_DEPLOY_AFTER gets, as it is until CI applies it;
# with --ignore-not-found that is no output and exit 0, as the real one does.
# STUB_GET_FAILS=1 fails every get of it, as an unreachable API server would.
# A patch records its --patch-file argument in $STUB_LOG.patch, then what it
# read from it. `-f -` records what kubectl read in $STUB_LOG.stdin.
cat > "$tmp/bin/kubectl" <<'STUB'
#!/usr/bin/env bash
printf 'kubectl KUBECONFIG=%s %s\n' "${KUBECONFIG:-}" "$*" >> "$STUB_LOG"
if [ "$1" = apply ] || [ "$1" = create ]; then
    case " $* " in *' -f - '*) cat > "$STUB_LOG.stdin" ;; esac
    exit 0
fi
if [ "$1" = patch ]; then
    while [ $# -gt 1 ]; do
        if [ "$1" = --patch-file ]; then
            { printf '%s\n' "$2"; cat "$2"; } > "$STUB_LOG.patch"
        fi
        shift
    done
    [ "${STUB_PATCH_FAILS:-0}" = 0 ]
    exit
fi
case "$1" in
    cluster-info) [ "${STUB_CLUSTER_UP:-0}" = 1 ] ;;
    get)
        case "$2" in
            namespace) exit 0 ;;
            deployment/*)
                [ "${STUB_GET_FAILS:-0}" = 0 ] \
                    || { echo 'Unable to connect to the server: dial tcp: i/o timeout' >&2; exit 1; }
                if [ "$(grep -c ' get deployment/' "$STUB_LOG")" -gt "${STUB_DEPLOY_AFTER:-0}" ]; then
                    case " $* " in *' -o name '*) echo "deployment.apps/${2#deployment/}" ;; esac
                else
                    case " $* " in *' --ignore-not-found '*) exit 0 ;; esac
                    echo 'Error from server (NotFound): deployments.apps not found' >&2; exit 1
                fi ;;
            *) exit 1 ;;
        esac ;;
    wait) [ "${STUB_ROLLOUT_FAILS:-0}" = 0 ] ;;
    rollout) [ "${STUB_RESTART_FAILS:-0}" = 0 ] ;;
    delete) exit 0 ;;
esac
STUB

cat > "$tmp/bin/helm" <<'STUB'
#!/usr/bin/env bash
printf 'helm KUBECONFIG=%s %s\n' "${KUBECONFIG:-}" "$*" >> "$STUB_LOG"
exit 0
STUB

cat > "$tmp/bin/eksctl" <<'STUB'
#!/usr/bin/env bash
printf 'eksctl %s\n' "$*" >> "$STUB_LOG"
case "$1" in
    version) [ "${STUB_EKSCTL_VERSION_FAILS:-0}" = 0 ] || exit 126
             printf '%s\n' "${STUB_EKSCTL_VERSION-0.230.0}" ;;
    get)    [ "${STUB_CLUSTER_UP:-0}" = 1 ] ;;
    delete) [ "${STUB_EKSCTL_FAILS:-0}" = 0 ] ;;
    utils|create) [ "${STUB_EKSCTL_CREATE_FAILS:-0}" = 0 ] ;;
esac
STUB

# Logged and instant, so a 30-minute poll runs in a second.
cat > "$tmp/bin/sleep" <<'STUB'
#!/usr/bin/env bash
printf 'sleep %s\n' "$*" >> "$STUB_LOG"
STUB

# Maven wrappers that see JDK 21, JDK 17, and no JDK at all. The first two
# print the line the real `./mvnw -v` prints.
for v in 21 17; do
    printf '#!/usr/bin/env bash\necho "Apache Maven 3.9.16"\necho "Java version: %s.0.8, vendor: Homebrew"\n' \
        "$v" > "$tmp/mvnw-$v"
done
printf '#!/usr/bin/env bash\necho "The JAVA_HOME environment variable is not defined correctly"\nexit 1\n' \
    > "$tmp/mvnw-none"
chmod +x "$tmp/bin/"* "$tmp"/mvnw-*

# ---------------------------------------------------------------------------
# The harness
# ---------------------------------------------------------------------------
FAILED=0
OUT="$tmp/out"
export STUB_LOG="$tmp/calls"

# run <script> [args...]: runs a copied script with the stubs first on PATH,
# and leaves its combined output in $OUT and its exit status in $STATUS.
run() {
    local script=$1; shift
    : > "$STUB_LOG"
    PATH="$tmp/bin:$PATH" ASSUME_YES=1 NO_COLOUR=1 KUBECONFIG="$tmp/caller-kubeconfig" \
        bash "$tmp/deploy/aws/$script" "$@" > "$OUT" 2>&1 < /dev/null
    STATUS=$?
}

# run_lib '<commands>': sources lib.sh under up.sh's `set -euo pipefail` and
# runs the commands, leaving the same $OUT and $STATUS.
run_lib() {
    : > "$STUB_LOG"
    PATH="$tmp/bin:$PATH" NO_COLOUR=1 \
        bash -c 'set -euo pipefail; . "$1"; eval "$2"' _ "$tmp/deploy/aws/lib.sh" "$1" \
        > "$OUT" 2>&1 < /dev/null
    STATUS=$?
}

pass() { printf '  ok    %s\n' "$1"; }
fail() {
    printf '  FAIL  %s: %s\n' "$1" "$2"
    sed 's/^/        | /' "$OUT" | tail -n 25
    FAILED=$((FAILED + 1))
}
expect_status() { [ "$STATUS" = "$2" ] || { fail "$1" "exit $STATUS, expected $2"; return 1; }; }
expect_out()    { grep -qF -- "$2" "$OUT" || { fail "$1" "no line containing '$2'"; return 1; }; }
reject_out()    { ! grep -qF -- "$2" "$OUT" || { fail "$1" "unexpected '$2'"; return 1; }; }
# mode_of <file>: its permission bits in octal, with GNU or BSD stat.
mode_of() { stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1"; }
# expect_calls <name> <pattern> <count>: the stub log has <count> matching lines.
expect_calls() {
    local n
    n=$(grep -c -- "$2" "$STUB_LOG")
    [ "$n" = "$3" ] || { fail "$1" "$n call(s) matching '$2', expected $3"; return 1; }
}

# The foundation's own leftovers: its stack and the ECR repository, which the
# regional query lists, and the retained GitHub OIDC provider. AWS reports IAM
# from us-east-1, so the real query would not list that one, but the filter
# drops it wherever it appears.
FOUNDATION_ARNS='arn:aws:cloudformation:ap-south-1:123456789012:stack/flight-ops-foundation/0f1e\tarn:aws:ecr:ap-south-1:123456789012:repository/flight-ops-service\tarn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com'

echo "down.sh"

name="--keep-foundation passes when only the foundation is left"
STUB_STACKS='' STUB_LIST_STACKS='flight-ops-foundation' STUB_TAGGED=$FOUNDATION_ARNS \
    run down.sh --keep-foundation
expect_status "$name" 0 && expect_out "$name" 'PASS  CloudFormation stacks' \
    && expect_out "$name" 'PASS  anything tagged Project=flight-ops' && pass "$name"

name="--keep-foundation still fails on a leftover that is not the foundation's"
STUB_STACKS='' STUB_LIST_STACKS='flight-ops-foundation flight-ops-data' \
    STUB_TAGGED="$FOUNDATION_ARNS\\tarn:aws:sqs:ap-south-1:123456789012:booking-events" \
    run down.sh --keep-foundation
expect_status "$name" 1 && expect_out "$name" 'FAIL  CloudFormation stacks: flight-ops-data' \
    && expect_out "$name" 'arn:aws:sqs:ap-south-1:123456789012:booking-events' \
    && reject_out "$name" 'oidc-provider/token.actions.githubusercontent.com' && pass "$name"

name="a full teardown fails on the same foundation leftovers"
STUB_STACKS='' STUB_LIST_STACKS='flight-ops-foundation' STUB_TAGGED=$FOUNDATION_ARNS \
    run down.sh
expect_status "$name" 1 && expect_out "$name" 'FAIL  CloudFormation stacks: flight-ops-foundation' \
    && expect_out "$name" 'FAIL  anything tagged Project=flight-ops' && pass "$name"

name="a stack still deleting when the waiter gives up fails the sweep"
STUB_STACKS='flight-ops-lambda' STUB_WAIT_FAILS=1 \
    STUB_LIST_STACKS='flight-ops-lambda:DELETE_IN_PROGRESS flight-ops-old:DELETE_COMPLETE' \
    run down.sh
expect_status "$name" 1 && expect_out "$name" 'will list it until it has gone' \
    && expect_out "$name" 'FAIL  CloudFormation stacks: flight-ops-lambda' \
    && reject_out "$name" 'flight-ops-old' && pass "$name"

name="a full teardown passes when only the retained OIDC provider is tagged"
STUB_STACKS='' STUB_LIST_STACKS='' \
    STUB_TAGGED='arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com' \
    run down.sh
expect_status "$name" 0 && expect_out "$name" 'PASS  anything tagged Project=flight-ops' && pass "$name"

name="unusable credentials stop down.sh and cost-check.sh before any other call"
cred_failed=0
for script in down.sh cost-check.sh; do
    STUB_STS_FAILS=1 run "$script"
    if ! { expect_status "$name: $script" 1 \
            && expect_out "$name: $script" 'AWS credentials are not usable' \
            && expect_calls "$name: $script" '^aws ' 1; }; then
        cred_failed=1
    fi
done
[ "$cred_failed" = 1 ] || pass "$name"

KUBECONFIG_STATE="$tmp/deploy/aws/.state/kubeconfig"

name="kubectl and helm use the cluster's own kubeconfig, never the caller's"
STUB_CLUSTER_UP=1 run down.sh
if ! grep -q '^kubectl ' "$STUB_LOG" || ! grep -q '^helm ' "$STUB_LOG"; then
    fail "$name" "kubectl or helm was never called"
elif grep -E '^(kubectl|helm) ' "$STUB_LOG" | grep -qvF "KUBECONFIG=$KUBECONFIG_STATE "; then
    fail "$name" "a call used a kubeconfig other than $KUBECONFIG_STATE"
elif ! grep -qF -- "eks update-kubeconfig --name flight-ops-cluster --region ap-south-1 --kubeconfig $KUBECONFIG_STATE" "$STUB_LOG"; then
    fail "$name" "update-kubeconfig did not write $KUBECONFIG_STATE"
elif [ -e "$tmp/caller-kubeconfig" ]; then
    fail "$name" "the caller's kubeconfig was written to"
else
    pass "$name"
fi

# The run above passed its sweep.
name="a clean teardown deletes the kubeconfig and lists what is left to do"
if expect_status "$name" 0 && expect_out "$name" "deleted $KUBECONFIG_STATE" \
        && expect_out "$name" 'set the variable DEPLOY_ENABLED to' \
        && expect_out "$name" "back to 'deploy (gated off)'" && expect_out "$name" 'unset KUBECONFIG'; then
    if [ -e "$KUBECONFIG_STATE" ]; then fail "$name" "$KUBECONFIG_STATE is still there"; else pass "$name"; fi
fi

name="an unreachable cluster skips the Helm and namespace steps"
STUB_CLUSTER_UP=0 run down.sh
if grep -q '^helm \|^kubectl .* delete ' "$STUB_LOG"; then
    fail "$name" "helm or a kubectl delete ran without a reachable cluster"
else
    expect_out "$name" 'skipped: the cluster is not reachable' && pass "$name"
fi

name="a delete that does not finish is never reported as done"
STUB_STACKS='flight-ops-lambda flight-ops-data flight-ops-foundation' STUB_WAIT_FAILS=1 \
    STUB_CLUSTER_UP=1 STUB_EKSCTL_FAILS=1 run down.sh
reject_out "$name" '✓ flight-ops-lambda deleted' && reject_out "$name" '✓ flight-ops-data deleted' \
    && reject_out "$name" '✓ flight-ops-foundation deleted' && reject_out "$name" '✓ cluster deleted' \
    && expect_out "$name" 'flight-ops-data did not finish deleting' && pass "$name"

# The real CLI applies the prefix query; the stub prints what it would return.
# IAM refuses to delete a policy that still has non-default versions. The
# listing's capture holds stderr as well, so a warning the CLI prints there on
# success must not be taken for policy ARNs.
LBC_ARN=arn:aws:iam::123456789012:policy/flight-ops-lbc
name="down.sh deletes the project's controller policies, versions first, and no other"
STUB_POLICIES="$LBC_ARN-v3.5.0\\t$LBC_ARN-v3.4.0" STUB_POLICY_VERSIONS=v1 STUB_POLICY_LIST_WARNS=1 run down.sh
if expect_status "$name" 0 \
        && expect_calls "$name" "^aws iam list-policies --scope Local .*starts_with(PolicyName, 'flight-ops-lbc-')" 1 \
        && expect_calls "$name" "^aws iam delete-policy --policy-arn $LBC_ARN-v3.5.0\$" 1 \
        && expect_calls "$name" "^aws iam delete-policy --policy-arn $LBC_ARN-v3.4.0\$" 1 \
        && expect_calls "$name" '^aws iam delete-policy ' 2 \
        && expect_calls "$name" 'AWSLoadBalancerControllerIAMPolicy' 0 \
        && reject_out "$name" 'could not delete' \
        && expect_out "$name" '✓ flight-ops-lbc-v3.5.0 deleted'; then
    version_at=$(grep -n "delete-policy-version --policy-arn $LBC_ARN-v3.5.0 " "$STUB_LOG" | cut -d: -f1)
    policy_at=$(grep -n "delete-policy --policy-arn $LBC_ARN-v3.5.0\$" "$STUB_LOG" | cut -d: -f1)
    if [ -n "$version_at" ] && [ "$version_at" -lt "$policy_at" ]; then
        pass "$name"
    else
        fail "$name" "the non-default version was not deleted before the policy"
    fi
fi

name="a failed policy listing is reported, not taken for no policy"
STUB_POLICY_LIST_FAILS=1 run down.sh
expect_out "$name" 'could not list IAM policies, so flight-ops-lbc-* is not deleted' \
    && expect_calls "$name" 'iam delete-policy' 0 && pass "$name"

# The re-run a failed teardown asks for needs the cluster's kubeconfig. The
# file starts group-readable, as one from an older checkout might be.
name="a failed teardown keeps the kubeconfig, mode 0600"
printf 'old\n' > "$KUBECONFIG_STATE" && chmod 644 "$KUBECONFIG_STATE"
STUB_CLUSTER_UP=1 STUB_LIST_STACKS='flight-ops-data' run down.sh
if expect_status "$name" 1 && reject_out "$name" "deleted $KUBECONFIG_STATE"; then
    if [ ! -f "$KUBECONFIG_STATE" ]; then
        fail "$name" "$KUBECONFIG_STATE is gone"
    elif [ "$(mode_of "$KUBECONFIG_STATE")" != 600 ]; then
        fail "$name" "mode $(mode_of "$KUBECONFIG_STATE"), expected 600"
    else
        pass "$name"
    fi
fi

# The plan prints before anything is deleted, so a run that fails says it too.
name="the teardown's plan says to set DEPLOY_ENABLED to false first"
expect_out "$name" 'Set the repository variable DEPLOY_ENABLED to' && pass "$name"

echo "cost-check.sh"

name="Cost Explorer being unavailable still reaches the budgets"
STUB_CE_FAILS=1 run cost-check.sh
expect_status "$name" 0 && expect_out "$name" 'Cost Explorer returned nothing' \
    && expect_out "$name" '==> Budgets' && expect_out "$name" 'flight-ops-daily' && pass "$name"

echo "ecr-image-exists.sh"

# Its stdout is appended to $GITHUB_OUTPUT, so stdout is checked on its own.
ecr_lookup() {
    : > "$STUB_LOG"
    PATH="$tmp/bin:$PATH" AWS_REGION=ap-south-1 STUB_ECR=$1 \
        bash "$tmp/deploy/aws/ecr-image-exists.sh" flight-ops-service abc123 \
        > "$tmp/stdout" 2> "$OUT" < /dev/null
    STATUS=$?
    STDOUT=$(cat "$tmp/stdout")
}

name="a tag in the registry is reported as exists=true"
ecr_lookup found
if expect_status "$name" 0 \
        && expect_calls "$name" 'describe-images --repository-name flight-ops-service --image-ids imageTag=abc123' 1; then
    if [ "$STDOUT" = exists=true ]; then pass "$name"; else fail "$name" "stdout was '$STDOUT'"; fi
fi

name="ImageNotFoundException is reported as exists=false"
ecr_lookup missing
if expect_status "$name" 0; then
    if [ "$STDOUT" = exists=false ]; then pass "$name"; else fail "$name" "stdout was '$STDOUT'"; fi
fi

name="any other error fails the lookup and reports nothing"
ecr_lookup denied
if [ "$STATUS" = 0 ]; then
    fail "$name" "exit 0 on AccessDeniedException"
elif [ -n "$STDOUT" ]; then
    fail "$name" "stdout was '$STDOUT'"
else
    expect_out "$name" 'AccessDeniedException' && pass "$name"
fi

echo "lib.sh"

name="stack_output prints nothing for an output the CLI reports as None"
STUB_STACKS='s' STUB_OUTPUT=None run_lib 'stack_output s Missing'
got=$(cat "$OUT")
if [ -z "$got" ]; then pass "$name"; else fail "$name" "printed '$got'"; fi

name="stack_output prints a real value"
STUB_STACKS='s' STUB_OUTPUT=vpc-0abc run_lib 'stack_output s VpcId'
got=$(cat "$OUT")
if [ "$got" = vpc-0abc ]; then pass "$name"; else fail "$name" "printed '$got'"; fi

# How up.sh calls it, under set -e. Any error but a missing stack must print
# its message before it ends the run.
# shellcheck disable=SC2016  # expanded by the shell run_lib starts
output_probe='vpc=$(stack_output s VpcId); echo "read [$vpc]"'

name="stack_output is empty for a missing stack and stops on any other error"
STUB_STACKS='' run_lib "$output_probe"
if expect_status "$name" 0 && expect_out "$name" 'read []'; then
    STUB_STACKS='s' STUB_DESCRIBE_FAILS=1 run_lib "$output_probe"
    expect_status "$name" 1 \
        && expect_out "$name" 'could not read output VpcId of stack s' \
        && expect_out "$name" 'Rate exceeded' && reject_out "$name" 'read [' \
        && pass "$name"
fi

name="stack_status is empty for a missing stack and the status for a real one"
STUB_STACKS='' run_lib 'stack_status s'
missing=$(cat "$OUT")
STUB_STACKS='s' run_lib 'stack_status s'
present=$(cat "$OUT")
if [ -z "$missing" ] && [ "$present" = CREATE_COMPLETE ]; then
    pass "$name"
else
    fail "$name" "got '$missing' and '$present'"
fi

echo "lib.sh: up.sh's checks"

# How up.sh step 6 calls it: an existing stack is used, a missing one created.
ready_probe='if stack_ready flight-ops-data; then echo READY; else echo ABSENT; fi'

name="stack_ready uses a finished stack and creates a missing one"
STUB_STACKS='flight-ops-data' STUB_STACK_STATUS=UPDATE_ROLLBACK_COMPLETE run_lib "$ready_probe"
if expect_status "$name" 0 && expect_out "$name" READY; then
    STUB_STACKS='' run_lib "$ready_probe"
    expect_status "$name" 0 && expect_out "$name" ABSENT && pass "$name"
fi

name="stack_ready stops on ROLLBACK_COMPLETE and says to delete the stack"
STUB_STACKS='flight-ops-data' STUB_STACK_STATUS=ROLLBACK_COMPLETE run_lib "$ready_probe"
expect_status "$name" 1 && expect_out "$name" 'aws cloudformation delete-stack --stack-name flight-ops-data' \
    && reject_out "$name" READY && reject_out "$name" ABSENT && pass "$name"

name="stack_ready stops on a stack still in progress and says to wait"
STUB_STACKS='flight-ops-data' STUB_STACK_STATUS=UPDATE_IN_PROGRESS run_lib "$ready_probe"
expect_status "$name" 1 && expect_out "$name" 'Wait for it to finish' \
    && reject_out "$name" ABSENT && pass "$name"

name="a failed describe-stacks is not taken for a missing stack"
STUB_STACKS='flight-ops-data' STUB_DESCRIBE_FAILS=1 run_lib "$ready_probe"
expect_status "$name" 1 && expect_out "$name" 'could not read the status of stack flight-ops-data' \
    && reject_out "$name" ABSENT && pass "$name"

# How up.sh step 4 calls it when the cluster already exists. The account also
# holds the GitHub provider and another cluster's.
GITHUB_OIDC='arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com'
OTHER_OIDC='arn:aws:iam::123456789012:oidc-provider/oidc.eks.ap-south-1.amazonaws.com/id/CD34'
OWN_OIDC='arn:aws:iam::123456789012:oidc-provider/oidc.eks.ap-south-1.amazonaws.com/id/AB12'
complete='complete_cluster cluster.yaml'

name="complete_cluster leaves a cluster with its addons, OIDC provider and node group alone"
STUB_OIDC_PROVIDERS="$GITHUB_OIDC\\t$OWN_OIDC" STUB_NODEGROUP=active run_lib "$complete"
expect_status "$name" 0 && expect_calls "$name" '^eksctl ' 0 && expect_calls "$name" '^aws eks create-addon' 0 \
    && expect_calls "$name" '^aws eks wait cluster-active --name flight-ops-cluster$' 1 \
    && expect_calls "$name" '^aws eks wait addon-active --cluster-name flight-ops-cluster --addon-name vpc-cni$' 1 \
    && expect_calls "$name" '^aws eks wait addon-active' 1 \
    && expect_calls "$name" '^aws eks wait nodegroup-active --cluster-name flight-ops-cluster --nodegroup-name ng-1$' 1 \
    && pass "$name"

name="complete_cluster creates only the missing networking addons, before the node group"
STUB_OIDC_PROVIDERS=$OWN_OIDC STUB_NODEGROUP=missing STUB_ADDONS_MISSING='kube-proxy coredns' run_lib "$complete"
if expect_status "$name" 0 && expect_calls "$name" '^aws eks create-addon .*--addon-name vpc-cni$' 0 \
        && expect_calls "$name" '^aws eks create-addon --cluster-name flight-ops-cluster --addon-name kube-proxy$' 1 \
        && expect_calls "$name" '^aws eks create-addon --cluster-name flight-ops-cluster --addon-name coredns$' 1; then
    last_addon=$(grep -n '^aws eks create-addon' "$STUB_LOG" | tail -1 | cut -d: -f1)
    created_at=$(grep -n '^eksctl create nodegroup' "$STUB_LOG" | cut -d: -f1)
    if [ -z "$created_at" ]; then
        fail "$name" "no eksctl create nodegroup"
    elif [ "$last_addon" -lt "$created_at" ]; then
        pass "$name"
    else
        fail "$name" "an addon was created after the node group"
    fi
fi

name="a failed addon lookup is not taken for a missing addon"
STUB_OIDC_PROVIDERS=$OWN_OIDC STUB_NODEGROUP=active STUB_ADDON_LOOKUP_FAILS=1 run_lib "$complete"
expect_status "$name" 1 && expect_out "$name" 'could not read addon vpc-cni' \
    && expect_calls "$name" '^aws eks create-addon' 0 && expect_calls "$name" '^eksctl ' 0 && pass "$name"

name="complete_cluster associates a missing OIDC provider, and another cluster's does not count"
STUB_OIDC_PROVIDERS="$GITHUB_OIDC\\t$OTHER_OIDC" STUB_NODEGROUP=active run_lib "$complete"
expect_status "$name" 0 \
    && expect_calls "$name" '^eksctl utils associate-iam-oidc-provider --config-file cluster.yaml --approve$' 1 \
    && expect_calls "$name" '^eksctl create ' 0 && pass "$name"

name="complete_cluster creates a missing node group, then waits for it"
STUB_OIDC_PROVIDERS=$OWN_OIDC STUB_NODEGROUP=missing run_lib "$complete"
if expect_status "$name" 0 \
        && expect_calls "$name" '^eksctl create nodegroup --config-file cluster.yaml --include ng-1$' 1 \
        && expect_calls "$name" '^eksctl utils ' 0; then
    created_at=$(grep -n '^eksctl create nodegroup' "$STUB_LOG" | cut -d: -f1)
    wait_at=$(grep -n 'eks wait nodegroup-active' "$STUB_LOG" | cut -d: -f1)
    if [ -z "$wait_at" ]; then
        fail "$name" "no nodegroup-active wait"
    elif [ "$wait_at" -gt "$created_at" ]; then
        pass "$name"
    else
        fail "$name" "the wait ran before the create"
    fi
fi

name="a failed lookup is not taken for a missing OIDC provider or node group"
STUB_OIDC_LIST_FAILS=1 STUB_NODEGROUP=active run_lib "$complete"
if expect_status "$name" 1 && expect_out "$name" 'could not list the IAM OIDC providers' \
        && expect_calls "$name" '^eksctl ' 0; then
    STUB_OIDC_PROVIDERS=$OWN_OIDC STUB_NODEGROUP=throttled run_lib "$complete"
    expect_status "$name" 1 && expect_out "$name" 'could not read node group ng-1' \
        && expect_calls "$name" '^eksctl ' 0 && pass "$name"
fi

name="complete_cluster stops on a cluster that does not become ACTIVE"
STUB_EKS_WAIT_FAILS=cluster-active run_lib "$complete"
expect_status "$name" 1 && expect_out "$name" 'cluster flight-ops-cluster is not ACTIVE' \
    && expect_calls "$name" '^eksctl ' 0 && pass "$name"

name="complete_cluster stops on vpc-cni or a node group that does not become ACTIVE"
STUB_OIDC_PROVIDERS=$OWN_OIDC STUB_NODEGROUP=active STUB_EKS_WAIT_FAILS=addon-active run_lib "$complete"
if expect_status "$name" 1 && expect_out "$name" 'addon vpc-cni did not become ACTIVE' \
        && expect_out "$name" 'aws eks describe-addon --region ap-south-1 --cluster-name flight-ops-cluster' \
        && expect_calls "$name" '^eksctl ' 0 && expect_calls "$name" 'eks describe-nodegroup' 0; then
    STUB_OIDC_PROVIDERS=$OWN_OIDC STUB_NODEGROUP=active STUB_EKS_WAIT_FAILS=nodegroup-active run_lib "$complete"
    expect_status "$name" 1 && expect_out "$name" 'node group ng-1 did not become ACTIVE' \
        && expect_out "$name" 'aws eks describe-nodegroup --region ap-south-1 --cluster-name flight-ops-cluster' \
        && reject_out "$name" 'is ACTIVE' && pass "$name"
fi

name="complete_cluster stops on a cluster with no OIDC issuer"
STUB_ISSUER=None STUB_NODEGROUP=active run_lib "$complete"
expect_status "$name" 1 && expect_out "$name" "cluster flight-ops-cluster has no OIDC issuer (got 'None')" \
    && expect_calls "$name" 'iam list-open-id-connect-providers' 0 && expect_calls "$name" '^eksctl ' 0 && pass "$name"

name="complete_cluster stops when an addon, the OIDC provider or the node group cannot be created"
STUB_OIDC_PROVIDERS=$OWN_OIDC STUB_NODEGROUP=missing STUB_ADDONS_MISSING=vpc-cni STUB_ADDON_CREATE_FAILS=1 \
    run_lib "$complete"
if expect_status "$name" 1 && expect_out "$name" 'could not create addon vpc-cni' \
        && expect_calls "$name" '^eksctl ' 0; then
    STUB_OIDC_PROVIDERS=$GITHUB_OIDC STUB_NODEGROUP=missing STUB_EKSCTL_CREATE_FAILS=1 run_lib "$complete"
    if expect_status "$name" 1 && expect_out "$name" 'could not associate an IAM OIDC provider with flight-ops-cluster' \
            && expect_calls "$name" '^eksctl create ' 0; then
        STUB_OIDC_PROVIDERS=$OWN_OIDC STUB_NODEGROUP=missing STUB_EKSCTL_CREATE_FAILS=1 run_lib "$complete"
        expect_status "$name" 1 && expect_out "$name" 'could not create node group ng-1' \
            && expect_calls "$name" 'eks wait nodegroup-active' 0 && pass "$name"
    fi
fi

name="lib.sh names the node group cluster.yaml declares"
: > "$OUT"
declared=$(sed -n '/^managedNodeGroups:/,/^[^ #]/s/^  - name: //p' "$here/cluster.yaml")
# shellcheck disable=SC2016  # expanded by the inner shell, after lib.sh is sourced
named=$(bash -c '. "$1"; printf %s "${NODEGROUP:-}"' _ "$tmp/deploy/aws/lib.sh")
if [ -n "$declared" ] && [ "$declared" = "$named" ]; then
    pass "$name"
else
    fail "$name" "cluster.yaml declares '$declared', lib.sh names '$named'"
fi

# How up.sh step 8 calls it.
name="ensure_metrics_server creates the addon only when it is missing"
run_lib ensure_metrics_server
if expect_status "$name" 0 \
        && expect_out "$name" 'addon metrics-server already exists (ACTIVE)' \
        && expect_calls "$name" '^aws eks create-addon' 0; then
    STUB_ADDONS_MISSING=metrics-server run_lib ensure_metrics_server
    expect_status "$name" 0 \
        && expect_out "$name" 'metrics-server addon requested' \
        && expect_calls "$name" \
            '^aws eks create-addon .*--addon-name metrics-server$' 1 \
        && pass "$name"
fi

name="ensure_metrics_server stops on a failed lookup or create"
STUB_ADDON_LOOKUP_FAILS=1 run_lib ensure_metrics_server
if expect_status "$name" 1 \
        && expect_out "$name" 'could not read addon metrics-server' \
        && expect_out "$name" 'ThrottlingException' \
        && expect_calls "$name" '^aws eks create-addon' 0; then
    STUB_ADDONS_MISSING=metrics-server STUB_ADDON_CREATE_FAILS=1 \
        run_lib ensure_metrics_server
    expect_status "$name" 1 \
        && expect_out "$name" 'could not create addon metrics-server' \
        && reject_out "$name" 'addon requested' && pass "$name"
fi

name="require_jdbc_url stops on None and on nothing, and accepts a PostgreSQL URL"
run_lib 'require_jdbc_url None'
if expect_status "$name" 1 && expect_out "$name" "no usable JdbcUrl output (got 'None')"; then
    run_lib "require_jdbc_url ''"
    if expect_status "$name" 1 && expect_out "$name" "(got 'nothing')"; then
        run_lib 'require_jdbc_url jdbc:postgresql://db.example:5432/flightops'
        expect_status "$name" 0 && pass "$name"
    fi
fi

name="require_jdk21 accepts JDK 21 and stops on JDK 17 or no JDK"
run_lib "require_jdk21 '$tmp/mvnw-21'"
if expect_status "$name" 0; then
    run_lib "require_jdk21 '$tmp/mvnw-17'"
    if expect_status "$name" 1 && expect_out "$name" "the Maven wrapper sees '17'"; then
        run_lib "require_jdk21 '$tmp/mvnw-none'"
        expect_status "$name" 1 && expect_out "$name" "the Maven wrapper sees 'no JDK'" && pass "$name"
    fi
fi

name="require_eksctl accepts 0.184.0 or later and stops on an older one or none"
STUB_EKSCTL_VERSION=0.230.0 run_lib require_eksctl
if expect_status "$name" 0; then
    STUB_EKSCTL_VERSION=1.0.0 run_lib require_eksctl
    if expect_status "$name" 0; then
        STUB_EKSCTL_VERSION=0.183.0 run_lib require_eksctl
        if expect_status "$name" 1 && expect_out "$name" "this one reports '0.183.0'"; then
            STUB_EKSCTL_VERSION='' run_lib require_eksctl
            expect_status "$name" 1 && expect_out "$name" "reports 'no version'" && pass "$name"
        fi
    fi
fi

# A broken install or a shim exits non-zero. Under set -e that must still
# reach the message, not end up.sh with nothing on screen.
name="require_eksctl names the problem when eksctl version itself fails"
STUB_EKSCTL_VERSION_FAILS=1 run_lib require_eksctl
expect_status "$name" 1 && expect_out "$name" "reports 'no version'" && pass "$name"

# The committed controller policy against the sum up.sh records, so a change
# to one without the other fails CI as well as the preflight.
policy_tag=$(sed -n 's/^LBC_POLICY_TAG=\([^ ]*\).*/\1/p' "$here/up.sh")
policy_sum=$(sed -n 's/^LBC_POLICY_SHA256=\([0-9a-f]*\).*/\1/p' "$here/up.sh")

name="require_sha256 accepts the committed controller policy at the sum up.sh records"
if [ -z "$policy_tag" ] || [ -z "$policy_sum" ]; then
    : > "$OUT"
    fail "$name" "up.sh sets no LBC_POLICY_TAG or LBC_POLICY_SHA256"
else
    run_lib "require_sha256 '$here/lbc-iam-policy-$policy_tag.json' $policy_sum"
    expect_status "$name" 0 && pass "$name"
fi

name="require_sha256 stops on a changed file and on a missing one"
printf '{}\n' > "$tmp/policy.json"
run_lib "require_sha256 '$tmp/policy.json' $policy_sum"
if expect_status "$name" 1 && expect_out "$name" "not the $policy_sum recorded for it"; then
    run_lib "require_sha256 '$tmp/absent.json' $policy_sum"
    expect_status "$name" 1 && expect_out "$name" "has sha256 'no file'" && pass "$name"
fi

EDIT=arn:aws:eks::aws:cluster-access-policy/AmazonEKSEditPolicy
grant="grant_namespace_access arn:aws:iam::123456789012:role/github-actions-deploy $EDIT flight-ops"

name="grant_namespace_access creates a missing entry and accepts the association it reads back"
STUB_ACCESS_ENTRY=0 STUB_ASSOCIATED=$EDIT run_lib "$grant"
expect_status "$name" 0 && expect_calls "$name" 'eks create-access-entry' 1 \
    && expect_calls "$name" 'eks list-associated-access-policies' 1 && pass "$name"

name="grant_namespace_access leaves an existing entry alone"
STUB_ACCESS_ENTRY=1 STUB_ASSOCIATED=$EDIT run_lib "$grant"
expect_status "$name" 0 && expect_calls "$name" 'eks create-access-entry' 0 && pass "$name"

name="grant_namespace_access accepts a refused associate call when the policy is already there"
STUB_ACCESS_ENTRY=1 STUB_ASSOCIATE_FAILS=1 STUB_ASSOCIATED=$EDIT run_lib "$grant"
expect_status "$name" 0 && pass "$name"

name="grant_namespace_access stops when nothing, or None, is read back"
STUB_ACCESS_ENTRY=1 STUB_ASSOCIATE_FAILS=1 STUB_ASSOCIATED='' run_lib "$grant"
if expect_status "$name" 1 && expect_out "$name" 'has no AmazonEKSEditPolicy scoped to namespace/flight-ops'; then
    STUB_ACCESS_ENTRY=1 STUB_ASSOCIATED=None run_lib "$grant"
    expect_status "$name" 1 && expect_out "$name" 'has no AmazonEKSEditPolicy' && pass "$name"
fi

name="wait_for_deployment waits for the deployment to appear, then waits once for it"
STUB_DEPLOY_AFTER=2 run_lib wait_for_deployment
if expect_status "$name" 0 && expect_calls "$name" ' get deployment/flight-ops' 3 \
        && expect_calls "$name" '^sleep 10$' 2 && expect_calls "$name" ' wait --for=condition=available' 1; then
    last_get=$(grep -n ' get deployment/' "$STUB_LOG" | tail -1 | cut -d: -f1)
    wait_at=$(grep -n ' wait --for' "$STUB_LOG" | cut -d: -f1)
    if [ "$wait_at" -gt "$last_get" ]; then pass "$name"; else fail "$name" "kubectl wait ran before the deployment existed"; fi
fi

name="wait_for_deployment stops after 30 minutes when CI never creates it"
STUB_DEPLOY_AFTER=1000 run_lib wait_for_deployment
expect_status "$name" 1 && expect_out "$name" 'DEPLOY_ENABLED is true and the run is on main' \
    && expect_calls "$name" ' get deployment/flight-ops' 180 \
    && expect_calls "$name" ' wait --for' 0 && pass "$name"

name="wait_for_deployment stops when the deployment never becomes available"
STUB_ROLLOUT_FAILS=1 run_lib wait_for_deployment
expect_status "$name" 1 && expect_out "$name" 'the deployment did not become available' && pass "$name"

name="use_private_kubeconfig exports the scripts' own file, mode 0600 under any umask"
rm -f "$KUBECONFIG_STATE"
# shellcheck disable=SC2016  # expanded by run_lib's shell
run_lib 'umask 000; use_private_kubeconfig; echo "KUBECONFIG=$KUBECONFIG"'
if expect_status "$name" 0 && expect_out "$name" "KUBECONFIG=$KUBECONFIG_STATE"; then
    if [ "$(mode_of "$KUBECONFIG_STATE")" = 600 ]; then pass "$name"
    else fail "$name" "mode $(mode_of "$KUBECONFIG_STATE"), expected 600"; fi
fi

name="use_private_kubeconfig tightens an existing file and keeps what it holds"
printf 'apiVersion: v1\n' > "$KUBECONFIG_STATE" && chmod 644 "$KUBECONFIG_STATE"
run_lib use_private_kubeconfig
if expect_status "$name" 0; then
    if [ "$(mode_of "$KUBECONFIG_STATE")" != 600 ]; then
        fail "$name" "mode $(mode_of "$KUBECONFIG_STATE"), expected 600"
    elif [ "$(cat "$KUBECONFIG_STATE")" != 'apiVersion: v1' ]; then
        fail "$name" "the contents changed"
    else
        pass "$name"
    fi
fi

STATE_ENV="$tmp/deploy/aws/.state/flight-ops.env"
# pending <value>: the state file records DB_PASSWORD_PENDING=<value>, or,
# with no value, has no state file at all.
pending() {
    rm -f "$STATE_ENV"
    [ -z "${1:-}" ] || printf 'DB_PASSWORD_PENDING=%s\n' "$1" > "$STATE_ENV"
}
# expect_pending <name> <value>: the state file now records <value>.
expect_pending() {
    local now
    now=$(grep '^DB_PASSWORD_PENDING=' "$STATE_ENV" 2>/dev/null | tail -1 | cut -d= -f2)
    [ "$now" = "$2" ] || { fail "$1" "DB_PASSWORD_PENDING is '$now', expected '$2'"; return 1; }
}
# in_argv <value>: some logged command line holds the value, as it is or in
# base64.
in_argv() {
    local encoded
    encoded=$(V=$1 python3 -c 'import base64, os; print(base64.b64encode(os.environ["V"].encode()).decode())')
    grep -qF -- "$1" "$STUB_LOG" || grep -qF -- "$encoded" "$STUB_LOG"
}
# patched <name> <password>: the patch reached kubectl on stdin and held only
# the password, base64-encoded under data, and no logged argument has it.
patched() {
    local patch_path patch_body
    patch_path=$(sed -n 1p "$STUB_LOG.patch" 2>/dev/null)
    patch_body=$(sed -n '2,$p' "$STUB_LOG.patch" 2>/dev/null)
    if [ -z "$patch_path" ]; then
        fail "$1" "kubectl patch was not given a --patch-file"
    elif [ "$patch_path" != /dev/stdin ]; then
        fail "$1" "the patch came from $patch_path, not stdin"
    elif ! printf '%s' "$patch_body" | V=$2 python3 -c '
import base64, json, os, sys
patch = json.load(sys.stdin)
sys.exit(not (list(patch) == ["data"] and list(patch["data"]) == ["DB_PASSWORD"]
              and base64.b64decode(patch["data"]["DB_PASSWORD"]).decode() == os.environ["V"]))' 2>/dev/null; then
        fail "$1" "the patch held '$patch_body'"
    elif in_argv "$2"; then
        fail "$1" "the password was on a command line"
    else
        return 0
    fi
    return 1
}
# The origin up.sh step 6 passes when it generated the password in this run,
# and the one for a DB_PASSWORD the shell exported.
reconcile='reconcile_secret_db_password "Ab12+cd34" generated'
inherited='reconcile_secret_db_password "Ab12+cd34" environment'
from_shell='DB_PASSWORD comes from your shell'

# A shell can still export DB_PASSWORD from an earlier run's instructions.
name="reconcile_secret_db_password leaves the Secret alone when no new database is pending"
pending ''
run_lib "$inherited"
if expect_status "$name" 0 && expect_out "$name" 'leaving it alone' \
        && expect_calls "$name" '^kubectl ' 0 && reject_out "$name" "$from_shell"; then
    pending none
    run_lib "$inherited"
    expect_status "$name" 0 && expect_calls "$name" '^kubectl ' 0 && expect_pending "$name" none \
        && pass "$name"
fi

name="reconcile_secret_db_password patches only DB_PASSWORD, on stdin, then restarts the pods"
pending secret; rm -f "$STUB_LOG.patch"
run_lib "$reconcile"
expect_status "$name" 0 && expect_calls "$name" ' patch secret flight-ops-secret -n flight-ops --type merge --patch-file ' 1 \
    && expect_calls "$name" ' rollout restart deployment/flight-ops -n flight-ops' 1 \
    && patched "$name" 'Ab12+cd34' && expect_pending "$name" none && pass "$name"

# The password step 6 generated in this same run is the new database's.
name="a password this run generated goes into the Secret without a warning"
pending secret; rm -f "$STUB_LOG.patch"
run_lib "$reconcile"
expect_status "$name" 0 && patched "$name" 'Ab12+cd34' && reject_out "$name" "$from_shell" \
    && reject_out "$name" 'modify-db-instance' && pass "$name"

# The re-run the lost-password stop further down asks for, or one from a
# shell that still exports a password from an earlier fix: nothing tells the
# two apart. The value holds characters JSON would need escaped, as an
# exported one may.
name="a DB_PASSWORD from the shell goes into the Secret with a warning that says how to put it right"
pending secret; rm -f "$STUB_LOG.patch"
# shellcheck disable=SC2016  # expanded by run_lib's shell
SHELL_DB_PASSWORD='Qw"e\r$ty' ALERT_EMAIL=ops@example.com \
    run_lib 'reconcile_secret_db_password "$SHELL_DB_PASSWORD" environment'
# shellcheck disable=SC2016  # the command as the warning prints it
expect_status "$name" 0 && expect_out "$name" "$from_shell" \
    && expect_out "$name" 'into the Secret unchecked' \
    && expect_out "$name" "password authentication failed" \
    && expect_out "$name" '--master-user-password "$DB_PASSWORD" --apply-immediately' \
    && expect_out "$name" 'ALERT_EMAIL=ops@example.com' \
    && expect_calls "$name" ' rollout restart deployment/flight-ops -n flight-ops' 1 \
    && patched "$name" 'Qw"e\r$ty' && expect_pending "$name" none && pass "$name"

# Only `generated` is up.sh's word for its own password. An ORIGIN left out,
# or any other word, counts as the environment's.
name="reconcile_secret_db_password warns unless ORIGIN is exactly generated"
origin_ok=1
for call in 'reconcile_secret_db_password "Ab12+cd34"' \
        'reconcile_secret_db_password "Ab12+cd34" Generated'; do
    pending secret; rm -f "$STUB_LOG.patch"
    run_lib "$call"
    if ! { expect_status "$name" 0 && expect_out "$name" "$from_shell" \
            && patched "$name" 'Ab12+cd34' && expect_pending "$name" none; }; then
        origin_ok=0
        break
    fi
done
[ "$origin_ok" = 0 ] || pass "$name"

name="reconcile_secret_db_password restarts nothing when CI has not created the deployment"
pending secret; rm -f "$STUB_LOG.patch"
STUB_DEPLOY_AFTER=1000 run_lib "$reconcile"
expect_status "$name" 0 && expect_calls "$name" ' rollout ' 0 && patched "$name" 'Ab12+cd34' \
    && expect_pending "$name" none && pass "$name"

# The run that created the database stopped before step 9, so its password is
# gone, and the Secret still holds the old database's.
name="reconcile_secret_db_password stops, patching nothing, when a pending password is lost"
pending secret
ALERT_EMAIL=ops@example.com run_lib 'reconcile_secret_db_password "" environment'
if expect_status "$name" 1 && expect_out "$name" "secret flight-ops-secret still holds the old database's password" \
        && expect_out "$name" "$STATE_ENV records a new database" \
        && expect_out "$name" 'aws rds modify-db-instance' && expect_calls "$name" '^kubectl ' 0 \
        && reject_out "$name" "$from_shell" && expect_pending "$name" secret; then
    # It ends with the re-run, as up.sh's message for a missing Secret does.
    case "$(tail -1 "$OUT")" in
        '    ALERT_EMAIL=ops@example.com '*) pass "$name" ;;
        *) fail "$name" "the last line is '$(tail -1 "$OUT")', not the re-run" ;;
    esac
fi

name="reconcile_secret_db_password stops on a failed patch, and the record stays"
pending secret; rm -f "$STUB_LOG.patch"
STUB_PATCH_FAILS=1 run_lib "$reconcile"
expect_status "$name" 1 && expect_out "$name" 'could not update DB_PASSWORD' \
    && expect_calls "$name" ' rollout ' 0 && patched "$name" 'Ab12+cd34' \
    && expect_pending "$name" secret && pass "$name"

name="after a failed restart, the re-run restarts the pods without the password"
pending secret
STUB_RESTART_FAILS=1 run_lib "$reconcile"
if expect_status "$name" 1 && expect_out "$name" 'could not restart deployment/flight-ops' \
        && expect_pending "$name" restart; then
    run_lib 'reconcile_secret_db_password ""'
    expect_status "$name" 0 && expect_calls "$name" ' patch ' 0 \
        && expect_calls "$name" ' rollout restart deployment/flight-ops' 1 \
        && expect_pending "$name" none && pass "$name"
fi

name="a failed deployment lookup is not taken for no deployment by reconcile_secret_db_password"
pending secret; rm -f "$STUB_LOG.patch"
STUB_GET_FAILS=1 run_lib "$reconcile"
expect_status "$name" 1 && expect_out "$name" 'could not tell whether deployment/flight-ops exists' \
    && expect_calls "$name" ' rollout ' 0 && patched "$name" 'Ab12+cd34' \
    && expect_pending "$name" restart && pass "$name"

# How up.sh step 9 creates a Secret that does not exist. The hashes carry
# bcrypt's $ signs, and the database password characters JSON would need
# escaped, as an exported one may.
name="create_secret hands kubectl the Secret on stdin, with no value in its arguments"
NEW_DB='Ab1"c\d+9'
# shellcheck disable=SC2016  # bcrypt's $ signs, kept literal
NEW_API='{bcrypt}$2y$10$abcdefghijklmnopqrstuuvwxyzABCDEFGHIJKLMNOPQRSTUV.'
# shellcheck disable=SC2016  # bcrypt's $ signs, kept literal
NEW_OPS='{bcrypt}$2y$10$0123456789./abcdefghijuWXYZabcdefghijklmnopqrstuvw'
rm -f "$STUB_LOG.stdin"
# shellcheck disable=SC2016  # expanded by run_lib's shell
NEW_DB=$NEW_DB NEW_API=$NEW_API NEW_OPS=$NEW_OPS \
    run_lib 'create_secret "$NEW_DB" "$NEW_API" "$NEW_OPS"'
if expect_status "$name" 0 && expect_calls "$name" '^kubectl ' 1 \
        && expect_calls "$name" '^kubectl KUBECONFIG=[^ ]* create -f -$' 1; then
    leaked=''
    for value in "$NEW_DB" "$NEW_API" "$NEW_OPS"; do
        in_argv "$value" && leaked="$leaked [$value]"
    done
    # The object `kubectl create secret generic --dry-run=client` printed:
    # these three keys, the name and namespace, and no labels.
    if [ -n "$leaked" ]; then
        fail "$name" "on a command line:$leaked"
    elif ! NEW_DB=$NEW_DB NEW_API=$NEW_API NEW_OPS=$NEW_OPS python3 -c '
import base64, json, os, sys
secret = json.load(open(sys.argv[1]))
want = {"DB_PASSWORD": os.environ["NEW_DB"], "API_PASSWORD": os.environ["NEW_API"],
        "OPS_PASSWORD": os.environ["NEW_OPS"]}
sys.exit(not (sorted(secret) == ["apiVersion", "data", "kind", "metadata"]
              and secret["apiVersion"] == "v1" and secret["kind"] == "Secret"
              and secret["metadata"] == {"name": "flight-ops-secret", "namespace": "flight-ops"}
              and {k: base64.b64decode(v).decode() for k, v in secret["data"].items()} == want))
' "$STUB_LOG.stdin" 2>/dev/null; then
        fail "$name" "kubectl read '$(cat "$STUB_LOG.stdin" 2>/dev/null)'"
    else
        pass "$name"
    fi
fi

# The checks above prove the helpers; this proves up.sh still calls them.
name="up.sh runs each of those checks"
: > "$OUT"
missing_calls=''
# shellcheck disable=SC2016  # the calls are matched as written, unexpanded
for call in 'require_jdk21 "$repo/mvnw"' 'require_eksctl' \
        'require_sha256 "$LBC_POLICY_FILE" "$LBC_POLICY_SHA256"' 'complete_cluster "$here/cluster.yaml"' \
        'grant_namespace_access ' 'if stack_ready "$DATA_STACK"' \
        'require_jdbc_url "$DB_URL"' 'ensure_metrics_server' \
        'use_private_kubeconfig' 'reconcile_secret_db_password "${DB_PASSWORD:-}" "$db_password_origin"' \
        'create_secret "$DB_PASSWORD" "$api_hash" "$ops_hash"' \
        '[ "$db_password_origin" = generated ] || warn_db_password_from_shell' 'wait_for_deployment'; do
    # At the start of a command line, so a comment or a `:` in front does not count.
    awk -v c="$call" '{ sub(/^[ \t]+/, "") } index($0, c) == 1 { f = 1 } END { exit !f }' "$here/up.sh" \
        || missing_calls="$missing_calls [$call]"
done
if [ -z "$missing_calls" ]; then pass "$name"; else fail "$name" "no call to$missing_calls"; fi

# eksctl writes a kubeconfig when it creates the cluster, so KUBECONFIG must
# name the scripts' own file before that, or the operator's is changed.
name="up.sh points KUBECONFIG at its own file before eksctl or the CLI write one"
line_of() { awk -v c="$1" '{ sub(/^[ \t]+/, "") } index($0, c) == 1 { print NR; exit }' "$here/up.sh"; }
private_at=$(line_of 'use_private_kubeconfig')
create_at=$(line_of 'eksctl create cluster')
update_at=$(line_of 'aws eks update-kubeconfig')
: > "$OUT"
if [ -z "$private_at" ] || [ -z "$create_at" ] || [ -z "$update_at" ]; then
    fail "$name" "a call is missing (lines '$private_at' '$create_at' '$update_at')"
elif [ "$private_at" -gt "$create_at" ] || [ "$private_at" -gt "$update_at" ]; then
    fail "$name" "use_private_kubeconfig is on line $private_at, after eksctl or update-kubeconfig"
else
    pass "$name"
fi

# Step 6 records the pending password between generating it and the deploy
# that can stop part way, so only a run that creates the database records it.
# Step 9 clears it once a new Secret holds the password, before the prompt
# that can end the run; reconcile_secret_db_password clears it for an old one.
name="up.sh records a new database's password as pending until a Secret holds it"
# shellcheck disable=SC2016  # matched as written, unexpanded
generated_at=$(line_of 'DB_PASSWORD=$(openssl rand')
recorded_at=$(line_of 'state_set DB_PASSWORD_PENDING secret')
# shellcheck disable=SC2016  # matched as written, unexpanded
deployed_at=$(line_of '--stack-name "$DATA_STACK"')
# shellcheck disable=SC2016  # matched as written, unexpanded
applied_at=$(line_of 'create_secret "$DB_PASSWORD"')
cleared_at=$(line_of 'state_set DB_PASSWORD_PENDING none')
saved_at=$(line_of 'confirm "Copy those two passwords')
: > "$OUT"
if [ -z "$generated_at" ] || [ -z "$recorded_at" ] || [ -z "$deployed_at" ] \
        || [ -z "$applied_at" ] || [ -z "$cleared_at" ] || [ -z "$saved_at" ]; then
    fail "$name" "a line is missing ('$generated_at' '$recorded_at' '$deployed_at' '$applied_at' '$cleared_at' '$saved_at')"
elif [ "$recorded_at" -lt "$generated_at" ] || [ "$recorded_at" -gt "$deployed_at" ]; then
    fail "$name" "the record is on line $recorded_at, not between lines $generated_at and $deployed_at"
elif [ "$cleared_at" -lt "$applied_at" ] || [ "$cleared_at" -gt "$saved_at" ]; then
    fail "$name" "the clear is on line $cleared_at, not between lines $applied_at and $saved_at"
elif [ "$(grep -c 'DB_PASSWORD_PENDING' "$here/up.sh")" != 2 ]; then
    fail "$name" "up.sh names DB_PASSWORD_PENDING somewhere else as well"
else
    pass "$name"
fi

# Step 9 writes DB_PASSWORD into an old Secret without a warning only when
# step 6 of the same run generated it, so the flag starts over on every run.
name="up.sh tells step 9 whether this run generated DB_PASSWORD"
# shellcheck disable=SC2016  # matched as written, unexpanded
ready_at=$(line_of 'if stack_ready "$DATA_STACK"')
inherited_at=$(line_of 'db_password_origin=environment')
generated_flag_at=$(line_of 'db_password_origin=generated')
: > "$OUT"
if [ -z "$ready_at" ] || [ -z "$inherited_at" ] || [ -z "$generated_flag_at" ]; then
    fail "$name" "a line is missing ('$ready_at' '$inherited_at' '$generated_flag_at')"
elif [ "$inherited_at" -gt "$ready_at" ]; then
    fail "$name" "db_password_origin starts on line $inherited_at, after the data stack check on line $ready_at"
elif [ "$generated_flag_at" -lt "$generated_at" ] || [ "$generated_flag_at" -gt "$deployed_at" ]; then
    fail "$name" "db_password_origin=generated is on line $generated_flag_at, not between lines $generated_at and $deployed_at"
elif [ "$(grep -c 'db_password_origin=' "$here/up.sh")" != 2 ]; then
    fail "$name" "up.sh sets db_password_origin somewhere else as well"
else
    pass "$name"
fi

# htpasswd -b and kubectl --from-literal take a value as an argument, where
# the process list shows it while the command runs.
name="up.sh hands htpasswd and kubectl no password or hash as an argument"
if grep -nE -- '--from-literal|htpasswd +-[A-Za-z]*b' "$here/up.sh" > "$OUT"; then
    fail "$name" "a value in a command's arguments"
else
    pass "$name"
fi

name="up.sh's closing summary prints the line that exports its kubeconfig"
: > "$OUT"
# shellcheck disable=SC2016  # matched as written, unexpanded
if awk '/^cat <<SUMMARY$/ { f = 1; next } /^SUMMARY$/ { f = 0 } f' "$here/up.sh" \
        | grep -qF -- 'export KUBECONFIG=$KUBECONFIG_FILE'; then
    pass "$name"
else
    fail "$name" "no 'export KUBECONFIG=\$KUBECONFIG_FILE' in the SUMMARY heredoc"
fi

# cluster.yaml's disablePodIMDS stops the controller reading them from IMDS.
name="up.sh gives the load balancer controller its region and VPC id"
: > "$OUT"
# shellcheck disable=SC2016  # matched as written, unexpanded
if grep -qF -- '--set "region=$AWS_REGION"' "$here/up.sh" \
        && grep -qF -- '--set "vpcId=$VPC_ID"' "$here/up.sh"; then
    pass "$name"
else
    fail "$name" "the helm install sets no region or no vpcId"
fi

# Another local user can leave a writable file or a symlink at a fixed name in
# /tmp, and rewrite whatever up.sh then reads back from it.
name="up.sh writes nothing to a fixed path in /tmp"
if grep -n '/tmp/' "$here/up.sh" > "$OUT"; then fail "$name" "a fixed /tmp path"; else pass "$name"; fi

echo
if [ "$FAILED" -gt 0 ]; then
    echo "selftest: $FAILED check(s) failed"
    exit 1
fi
echo "selftest: all checks passed"
