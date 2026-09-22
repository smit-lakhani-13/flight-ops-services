#!/usr/bin/env bash
# Prints exists=true or exists=false for one tag in an ECR repository, in the
# form a GitHub Actions step output takes. The deploy job runs it to skip the
# build and push for a commit that is already in the registry.
#
#   deploy/aws/ecr-image-exists.sh REPOSITORY TAG >> "$GITHUB_OUTPUT"
#
# Only ImageNotFoundException means "not there". Any other failure (a missing
# permission, a throttle) exits 1 with the CLI's message on stderr. Guessing
# "not there" would rebuild the image and then fail at the push, because the
# repository's tags are immutable.
set -euo pipefail

[ $# -eq 2 ] || { echo "usage: $0 REPOSITORY TAG" >&2; exit 2; }
repository=$1 tag=$2

if out=$(aws ecr describe-images \
        --repository-name "$repository" \
        --image-ids "imageTag=$tag" \
        --region "${AWS_REGION:?AWS_REGION is not set}" 2>&1); then
    echo "$tag is already in ECR; reusing it, not rebuilding" >&2
    echo 'exists=true'
elif grep -q ImageNotFoundException <<<"$out"; then
    echo 'exists=false'
else
    printf '%s\n' "$out" >&2
    echo "could not check ECR for $tag" >&2
    exit 1
fi
