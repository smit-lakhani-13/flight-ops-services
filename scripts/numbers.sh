#!/usr/bin/env bash
# Every number the README claims, recomputed from the tree, so the counts are
# read off a command instead of remembered.
#
# Test totals come from the surefire XML, so they count what ran. They need a
# build first:
#
#   ./mvnw -B clean verify && ./mvnw -B -f lambda/pom.xml clean verify
#   scripts/numbers.sh
#
# Everything else is computed from git, so it is correct in a clean clone.
set -euo pipefail
cd "$(dirname "$0")/.."

rule() { printf '\n%s\n%s\n' "$1" "$(printf '%.0s-' $(seq ${#1}))"; }

rule 'Source tree'
main_files=$(git ls-files 'src/main/java/**/*.java' | wc -l | tr -d ' ')
main_lines=$(git ls-files 'src/main/java/**/*.java' | xargs cat | wc -l | tr -d ' ')
printf 'src/main/java          %s files, %s lines\n' \
  "$main_files" "$(printf "%'d" "$main_lines")"
printf 'dto/ records           %s\n' \
  "$(git ls-files 'src/main/java/**/dto/*.java' | wc -l | tr -d ' ')"
printf 'exception/ classes     %s (one of them GlobalExceptionHandler)\n' \
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
  git ls-files 'k8s/**/*.yaml' 'k8s/*.yaml' \
    | grep -v 'kustomization.yaml' \
    | grep -v 'secret.example.yaml' \
    | grep -c "$1"
}
printf 'k8s base manifests     %s\n'   "$(k8s_manifests '^k8s/base/')"
printf 'k8s overlay patches    %s\n'   "$(k8s_manifests '^k8s/overlays/')"
printf 'k8s ingress component  %s\n'   "$(k8s_manifests '^k8s/components/')"
printf 'k8s cluster-scoped     %s (namespace.yaml)\n' "$(k8s_manifests '^k8s/[^/]*\.yaml$')"
printf 'kustomizations         %s\n'   "$(git ls-files 'k8s/**/kustomization.yaml' | wc -l | tr -d ' ')"
printf 'CloudFormation/deploy  %s templates, %s scripts\n' \
  "$(git ls-files 'deploy/aws/*.yaml' | wc -l | tr -d ' ')" \
  "$(git ls-files 'deploy/aws/*.sh' | wc -l | tr -d ' ')"
printf 'ADRs                   %s\n' \
  "$(git ls-files 'adr/[0-9]*.md' | wc -l | tr -d ' ')"
printf 'SQS fixtures           %s\n' "$(git ls-files 'events/*.json' | wc -l | tr -d ' ')"

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

if [ "$app_run" = 'n/a' ] || [ "$lambda_run" = 'n/a' ]; then
  echo 'no surefire reports -- run both builds first (see the header)'
else
  printf 'app module             %s declared, %s skipped, %s executed\n' \
    "$app_run" "$app_skipped" "$((app_run - app_skipped))"
  printf 'lambda module          %s\n' "$lambda_run"
  printf 'both modules           %s declared, %s run without Docker\n' \
    "$((app_run + lambda_run))" "$((app_run - app_skipped + lambda_run))"
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
