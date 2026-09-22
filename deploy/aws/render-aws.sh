#!/usr/bin/env bash
# Renders the EKS manifests, fully substituted, to stdout.
#
# The one render path for the six resources the aws overlay owns: Deployment,
# Service, ConfigMap, ServiceAccount, HPA, PDB. The deploy job applies its
# output, infra-lint validates it, and a person checking what is about to be
# applied runs it by hand. One path means the manifests CI checks are the ones
# the cluster gets.
#
# Two manifests are left out, and up.sh applies both with `kubectl apply -f`:
#
#   k8s/namespace.yaml                     cluster-scoped, and CI's access entry
#                                          is namespace-scoped, so CI may not
#                                          apply it even once
#   k8s/components/ingress/ingress.yaml    opt-in, and it bills an ALB from the
#                                          moment it exists
#
# Neither is in the overlay, so neither can arrive by accident on a push.
#
#   AWS_ACCOUNT_ID=123456789012 \
#   IMAGE_TAG=$(git rev-parse HEAD) \
#   SQS_QUEUE_URL=https://sqs.ap-south-1.amazonaws.com/123456789012/booking-events \
#   DB_URL=jdbc:postgresql://host:5432/flightops \
#     deploy/aws/render-aws.sh | kubectl apply -f -
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$here/../.." && pwd)

# Every variable is required. Substituting what is set and leaving the rest
# empty gives a manifest that applies and then fails in the cluster. An empty
# SQS_QUEUE_URL beside APP_EVENTS_PUBLISHER=sqs is a rollout that applies
# cleanly and then crash-loops, because SqsEventPublisher refuses a blank queue
# URL at startup. Here a missing value fails before anything reaches the
# cluster.
missing=()
for name in AWS_ACCOUNT_ID IMAGE_TAG SQS_QUEUE_URL DB_URL; do
    if [ -z "${!name:-}" ]; then
        missing+=("$name")
    fi
done
if [ ${#missing[@]} -gt 0 ]; then
    printf 'render-aws.sh: unset or empty: %s\n' "${missing[*]}" >&2
    printf 'All four are required. See the header of this script.\n' >&2
    exit 2
fi

# envsubst gets an explicit variable list. Unrestricted, it would replace any
# other $NAME in the manifests (a shell fragment in a lifecycle hook, a JVM
# flag) with an empty string, and say nothing.
# shellcheck disable=SC2016  # envsubst needs the literal names, so the shell
# must not expand them first; expanded, the list would arrive empty.
rendered=$(kubectl kustomize "$repo/k8s/overlays/aws" \
    | envsubst '${AWS_ACCOUNT_ID} ${IMAGE_TAG} ${SQS_QUEUE_URL} ${DB_URL}')

# A placeholder that survives means something was renamed in the overlay and
# not here. Applying it would annotate the ServiceAccount with a literal
# dollar sign.
# shellcheck disable=SC2016  # searching for a literal ${, not expanding one
if printf '%s' "$rendered" | grep -q '\${'; then
    # shellcheck disable=SC2016
    printf 'render-aws.sh: an unsubstituted ${...} placeholder survived:\n' >&2
    # shellcheck disable=SC2016
    printf '%s' "$rendered" | grep -n '\${' >&2
    exit 3
fi

# ConfigMap values must stay strings. kustomize drops quotes it considers
# unnecessary, so the overlay's `DB_URL: "${DB_URL}"` is emitted as
# `DB_URL: ${DB_URL}`, and envsubst then writes a bare scalar into it. YAML
# reads a bare `no` or `off` as a boolean and `12345` as an integer, and the
# API rejects the ConfigMap:
#
#   ConfigMap flight-ops-config is invalid: got boolean, want null or string
#
# A JDBC URL will not trip this, but the next value someone adds might. So
# these four ConfigMap keys (the two the overlay substitutes, plus AWS_REGION
# and DB_USER) are always re-quoted.
rendered=$(printf '%s' "$rendered" | sed -E \
    's/^([[:space:]]+)(SQS_QUEUE_URL|DB_URL|AWS_REGION|DB_USER):[[:space:]]*(.*[^[:space:]])[[:space:]]*$/\1\2: "\3"/')

# That doubles the quotes on a value kustomize did quote, so undo it.
rendered=$(printf '%s' "$rendered" | sed -E 's/: ""(.*)""$/: "\1"/')

printf '%s\n' "$rendered"
