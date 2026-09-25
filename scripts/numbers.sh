#!/usr/bin/env bash
# Every number the documentation claims, recomputed from the tree, so the
# counts are read off a command instead of remembered.
#
# Test totals come from the surefire XML, so they count what ran. They need a
# build first:
#
#   ./mvnw -B clean verify && ./mvnw -B -f lambda/pom.xml clean verify
#   scripts/numbers.sh
#
# Everything else is computed from git, so it is correct in a clean clone.
#
#   scripts/numbers.sh --check-readme
#
# prints the same, then exits 1 unless every README line that names adr/ and
# gives a count ("N records" or "N decision records") gives the number of
# records in adr/, and adr/README.md's index links each record exactly once and
# nothing else. docs-check runs it, so a pull request that adds a record without
# updating both fails.
# Test totals are not checked: they need a build.
set -euo pipefail
cd "$(dirname "$0")/.."

check_readme=0
case "${1:-}" in
  '') ;;
  --check-readme) check_readme=1 ;;
  *) echo "usage: scripts/numbers.sh [--check-readme]" >&2; exit 2 ;;
esac

rule() { printf '\n%s\n%s\n' "$1" "$(printf '%.0s-' $(seq ${#1}))"; }

rule 'Source tree'
main_files=$(git ls-files 'src/main/java/**/*.java' | wc -l | tr -d ' ')
main_lines=$(git ls-files 'src/main/java/**/*.java' | xargs cat | wc -l | tr -d ' ')
printf 'src/main/java          %s files, %s lines\n' \
  "$main_files" "$(printf "%'d" "$main_lines")"
printf 'dto/ records           %s\n' \
  "$(git ls-files 'src/main/java/**/dto/*.java' | wc -l | tr -d ' ')"
printf 'exception/ classes     %s (GlobalExceptionHandler and ApiErrorController among them)\n' \
  "$(git ls-files 'src/main/java/**/exception/*.java' | wc -l | tr -d ' ')"
printf 'Flyway migrations      %s (%s)\n' \
  "$(git ls-files 'src/main/resources/db/migration/*.sql' | wc -l | tr -d ' ')" \
  "$(git ls-files 'src/main/resources/db/migration/*.sql' | sed 's#.*/\(V[0-9]*\)__.*#\1#' | paste -sd, -)"
# *Test.java only: src/test/java also holds support classes, and a
# @TestConfiguration is not a test class.
printf 'test classes (app)     %s\n' \
  "$(git ls-files 'src/test/java/**/*Test.java' | wc -l | tr -d ' ')"
printf 'test classes (lambda)  %s\n' \
  "$(git ls-files 'lambda/src/test/java/**/*Test.java' | wc -l | tr -d ' ')"
# Manifests only: kustomization.yaml files are assembly instructions, not
# resources, and secret.example.yaml is a template that is never applied.
k8s_manifests() {
  git ls-files 'deploy/k8s/**/*.yaml' 'deploy/k8s/*.yaml' \
    | grep -v 'kustomization.yaml' \
    | grep -v 'secret.example.yaml' \
    | grep -c "$1"
}
printf 'k8s base manifests     %s\n'   "$(k8s_manifests '^deploy/k8s/base/')"
printf 'k8s overlay patches    %s\n'   "$(k8s_manifests '^deploy/k8s/overlays/')"
printf 'k8s ingress component  %s\n'   "$(k8s_manifests '^deploy/k8s/components/')"
printf 'k8s cluster-scoped     %s (namespace.yaml)\n' "$(k8s_manifests '^deploy/k8s/[^/]*\.yaml$')"
printf 'kustomizations         %s\n'   "$(git ls-files 'deploy/k8s/**/kustomization.yaml' | wc -l | tr -d ' ')"
# cluster.yaml is an eksctl config, not a CloudFormation template.
printf 'CloudFormation/deploy  %s templates, %s scripts\n' \
  "$(git ls-files 'deploy/aws/*.yaml' ':!deploy/aws/cluster.yaml' | wc -l | tr -d ' ')" \
  "$(git ls-files 'deploy/aws/*.sh' | wc -l | tr -d ' ')"
adrs=$(git ls-files 'adr/[0-9]*.md' | wc -l | tr -d ' ')
printf 'ADRs                   %s\n' "$adrs"
printf 'SQS fixtures           %s\n' "$(git ls-files 'lambda/events/*.json' | wc -l | tr -d ' ')"

rule 'Tests that ran'
count() {  # sums an attribute across every surefire XML under a directory
  local dir=$1 attribute=$2
  [ -d "$dir" ] || { echo 'n/a'; return; }
  grep -ho "$attribute=\"[0-9]*\"" "$dir"/TEST-*.xml 2>/dev/null \
    | sed "s/$attribute=\"\([0-9]*\)\"/\1/" | paste -sd+ - | bc
}
app_run=$(count target/surefire-reports tests)
app_skipped=$(count target/surefire-reports skipped)
lambda_run=$(count lambda/target/surefire-reports tests)
lambda_skipped=$(count lambda/target/surefire-reports skipped)

if [ "$app_run" = 'n/a' ] || [ "$lambda_run" = 'n/a' ]; then
  echo 'no surefire reports -- run both builds first (see the header)'
else
  printf 'app module             %s declared, %s skipped, %s executed\n' \
    "$app_run" "$app_skipped" "$((app_run - app_skipped))"
  printf 'lambda module          %s declared, %s skipped, %s executed\n' \
    "$lambda_run" "$lambda_skipped" "$((lambda_run - lambda_skipped))"
  printf 'both modules           %s declared, %s executed\n' \
    "$((app_run + lambda_run))" "$((app_run - app_skipped + lambda_run - lambda_skipped))"
  rule 'Per class (the Tests table adds these up)'
  for dir in target/surefire-reports lambda/target/surefire-reports; do
    [ -d "$dir" ] || continue
    grep -h 'Tests run' "$dir"/*.txt | sed 's/^Tests run: /  /;s/, Failures.*-- in / in /' \
      | awk '{printf "%-6s %s\n", $1, $NF}' | sort -b -k2
  done
fi

rule 'Versions'
# The project's own <version> is the second in the file; the first belongs to
# <parent>. awk, because BSD sed (macOS) and GNU sed disagree about `q` inside
# a block.
printf 'project                %s\n' \
  "$(awk '/<version>/ {n++; if (n==2) {gsub(/.*<version>|<\/version>.*/, ""); print; exit}}' pom.xml)"
printf 'Spring Boot            %s\n' \
  "$(grep -m1 -A2 '<artifactId>spring-boot-starter-parent' pom.xml | sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p')"
printf 'Java release           %s\n' \
  "$(sed -n 's/.*<java.version>\(.*\)<\/java.version>.*/\1/p' pom.xml)"
echo

if [ "$check_readme" -eq 1 ]; then
  rule 'README check'
  # Only lines that name adr/, so "8 records" about the dto/ package never
  # counts. Every distinct figure, so two phrases that disagree fail as well as
  # one that is wrong.
  claimed=$(grep -F 'adr/' README.md | grep -o -E '[0-9]+ (decision )?records' \
    | cut -d' ' -f1 | sort -u || true)
  # The index's link targets against the files, both sorted and not deduplicated,
  # so a missing, extra or repeated row fails, not only a wrong count.
  indexed=$(grep -o -E '^\| \[[0-9]{4}\]\([0-9]{4}-[^)]*\.md\)' adr/README.md \
    | sed -E 's/.*\((.*)\)$/adr\/\1/' | sort || true)
  records=$(git ls-files 'adr/[0-9]*.md' | sort)
  indexed_n=$(printf '%s' "$indexed" | grep -c . || true)
  if [ -z "$claimed" ]; then
    echo "README.md: no line names adr/ with an 'N records' count; adr/ holds $adrs"
    exit 1
  elif [ "$(printf '%s\n' "$claimed" | wc -l | tr -d ' ')" -ne 1 ]; then
    echo "README.md: more than one ADR count ($(printf '%s' "$claimed" | paste -sd, -)); adr/ holds $adrs"
    exit 1
  elif [ "$claimed" != "$adrs" ]; then
    echo "README.md says $claimed records; adr/ holds $adrs"
    exit 1
  elif [ "$indexed" != "$records" ]; then
    echo "adr/README.md's index does not link each record in adr/ once (< index, > adr/):"
    diff <(printf '%s\n' "$indexed") <(printf '%s\n' "$records") | grep '^[<>]' || true
    exit 1
  fi
  echo "README.md says $claimed records, adr/README.md indexes $indexed_n, and adr/ holds $adrs"
fi
