#!/usr/bin/env bash
# Renders the EKS manifests, fully substituted, to stdout.
#
# This is the ONE render path for the six resources the aws overlay owns —
# Deployment, Service, ConfigMap, ServiceAccount, HPA, PDB. CI uses it,
# infra-lint validates its output, up.sh uses it, and a human checking what is
# about to be applied uses it, because three ways of producing the manifests is
# three ways for them to differ and the difference only shows up in the cluster.
#
# Two manifests are deliberately NOT rendered here, and both are applied by
# up.sh with `kubectl apply -f`:
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

# Every variable is required. The alternative — substituting what is set and
# leaving the rest — produces a manifest that applies cleanly and runs wrong:
# an empty SQS_QUEUE_URL beside APP_EVENTS_PUBLISHER=sqs is a pod that goes
# Ready and fails every publish, which is the single worst failure shape
# available here. Missing values fail before anything reaches the cluster.
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

# envsubst is given an explicit variable list rather than being let loose on
# the whole document. Unrestricted, it would also eat any other $NAME in the
# manifests — a Kubernetes $(FIELD) reference, a shell fragment in a lifecycle
# hook, a JVM flag — and replace it with an empty string, silently.
# shellcheck disable=SC2016  # the single quotes are the point: envsubst is
# given the literal names to substitute, and the shell must not expand them
# first -- expanded, the list would arrive already substituted and empty.
rendered=$(kubectl kustomize "$repo/k8s/overlays/aws" \
    | envsubst '${AWS_ACCOUNT_ID} ${IMAGE_TAG} ${SQS_QUEUE_URL} ${DB_URL}')

# Belt and braces: if a placeholder survives, something was renamed in the
# overlay and not here, and applying it would create a ServiceAccount
# annotated with a literal dollar sign.
# shellcheck disable=SC2016  # searching for a literal ${, not expanding one
if printf '%s' "$rendered" | grep -q '\${'; then
    # shellcheck disable=SC2016
    printf 'render-aws.sh: an unsubstituted ${...} placeholder survived:\n' >&2
    # shellcheck disable=SC2016
    printf '%s' "$rendered" | grep -n '\${' >&2
    exit 3
fi

# ConfigMap values must stay strings, and one step above can stop them being
# strings. kustomize drops quotes it considers unnecessary, so the overlay's
# `DB_URL: "${DB_URL}"` is emitted as `DB_URL: ${DB_URL}` — and envsubst then
# writes a bare scalar into it. YAML reads a bare `y`, `no`, `on`, `off` as a
# BOOLEAN and a bare `12345` as an integer, and the Kubernetes API rejects a
# ConfigMap whose value is not a string:
#
#   ConfigMap flight-ops-config is invalid: got boolean, want null or string
#
# A JDBC URL will not trip this. A queue name, a tag, or the next value someone
# adds here might, and the failure arrives at apply time with a message about
# JSON schemas. Re-quoting the four substituted keys is two lines and removes
# the class of bug rather than this instance of it.
rendered=$(printf '%s' "$rendered" | sed -E \
    's/^([[:space:]]+)(SQS_QUEUE_URL|DB_URL|AWS_REGION|DB_USER):[[:space:]]*(.*[^[:space:]])[[:space:]]*$/\1\2: "\3"/')

# ...which would double the quotes on a value kustomize DID quote, so undo that.
rendered=$(printf '%s' "$rendered" | sed -E 's/: ""(.*)""$/: "\1"/')

printf '%s\n' "$rendered"
