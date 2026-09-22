#!/usr/bin/env bash
# Shared helpers for the deploy scripts. Sourced, never executed.
#
# Everything here exists because of a specific way these scripts can go wrong:
# a step that half-succeeds and is re-run, a region that quietly differs
# between two commands, a teardown that cannot find what the creation named, or
# a prompt that somebody answers "y" to without reading.

# Region is pinned, exported, and not taken from the caller's profile.
#
# Cross-region traffic is billed and cross-region references mostly just fail,
# but the real reason is subtler: `aws configure get region` can differ between
# the shell that created a stack and the shell that deletes it -- same account,
# same scripts, and the teardown reports a clean sweep because it is looking in
# an empty region. Pinning it here means every call in every script agrees.
export AWS_REGION=ap-south-1
export AWS_DEFAULT_REGION=$AWS_REGION

# Where a run records what it created, so a later run (or down.sh) can find it.
# Ignored by git -- it holds account ids and endpoints.
STATE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/.state"
STATE_FILE="$STATE_DIR/flight-ops.env"

# Names, in one place. up.sh creates them and down.sh deletes them, and a
# name that differs between the two is a resource nobody finds again.
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

# Fails early and legibly rather than letting the first AWS call fail with an
# opaque token error twenty seconds in.
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

# Idempotent: re-running a step overwrites its value rather than appending a
# second one, so sourcing the file twice cannot give two different answers.
state_set() {
    local key=$1 value=$2
    state_init
    if grep -q "^${key}=" "$STATE_FILE" 2>/dev/null; then
        # A temporary file, not sed -i: BSD sed (macOS) and GNU sed disagree
        # about -i's argument, and these scripts run on both.
        #
        # `|| true` because grep exits 1 when it selects NO lines, and it
        # selects no lines precisely when the file holds this key and nothing
        # else. up.sh runs under `set -e`, so without this the script dies
        # inside a bookkeeping helper -- and it dies on the re-run after a
        # first run that failed early enough to have written exactly one key,
        # which is the single most likely way anybody gets here.
        grep -v "^${key}=" "$STATE_FILE" > "$STATE_FILE.tmp" || true
        mv "$STATE_FILE.tmp" "$STATE_FILE"
    fi
    printf '%s=%s\n' "$key" "$value" >> "$STATE_FILE"
}

# Prints the value for a key, or exits 1 if the file or the key is absent.
#
# The exit status is the point, and an earlier version did not have it: with
# `grep ... | tail -1`, the pipeline's status is tail's, and tail succeeds on
# empty input. A missing key returned an empty string and status 0, so a caller
# that checked the status could not tell "not set" from "set to nothing".
state_get() {
    local key=$1 line
    [ -f "$STATE_FILE" ] || return 1
    line=$(grep "^${key}=" "$STATE_FILE" | tail -1)
    [ -n "$line" ] || return 1
    printf '%s' "${line#*=}"
}

# A typed word, not a keystroke. These prompts guard things that cost money or
# delete data, and "press y to continue" is answered reflexively.
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

stack_output() {
    aws cloudformation describe-stacks --stack-name "$1" \
        --query "Stacks[0].Outputs[?OutputKey=='$2'].OutputValue" --output text 2>/dev/null
}

# eksctl writes its own CloudFormation stack; its outputs are the only reliable
# source for the VPC and subnet ids the data stack needs.
eksctl_stack_output() {
    stack_output "eksctl-${CLUSTER_NAME}-cluster" "$1"
}
