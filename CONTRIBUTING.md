# Contributing

## Use JDK 21

**Java 21, not 25.** Spring Boot 4.1 supports 25, but the build is pinned to 21.
`maven-enforcer` stops the build on any other JDK before anything compiles, and
its message says what to set: `This build needs JDK 21. On this Mac: export
JAVA_HOME=/opt/homebrew/opt/openjdk@21`.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21      # macOS, Homebrew
export PATH="$JAVA_HOME/bin:$PATH"
java -version                                      # must say 21
```

## Building

`lambda/` is a separate module with no parent and its own dependency tree, so
it has its own Maven build:

```bash
./mvnw -B clean verify                       # the service
./mvnw -B -f lambda/pom.xml clean verify     # the consumer
```

The gate is `verify`, and `test` is not enough. `verify` is what runs JaCoCo's
threshold check, the enforcer rules and the CycloneDX SBOM.

### `Skipped: 7` is correct

Seven tests are in two classes annotated
`@Testcontainers(disabledWithoutDocker = true)`: `BookingIntegrationTest` and
`service/OutboxPrunePostgresTest`. Without a container runtime they skip, and
the build is still green and still correct. CI runs them, and that is where the
Flyway migrations and the outbox's native SQL run against PostgreSQL.

A new migration is not accepted until CI has gone green on it. The local H2
profile never sees it, and neither does a laptop with no Docker.

Today the service declares 204 tests and runs 197, and the Lambda runs 23. Do
not edit those numbers by hand anywhere:

```bash
scripts/numbers.sh          # recomputes every count the README claims
```

## Running it

```bash
./mvnw spring-boot:run       # H2 in memory, seeded, no setup
./demo.sh                    # the eight acts, with pauses
./demo.sh --fast             # without
```

Against real PostgreSQL:

```bash
docker compose up --build
```

For anything containing `${…}`, use environment variables instead of
`-Dspring-boot.run.arguments`. The shell mangles the argument form, and the
resulting failure blames the property.

## What CI enforces

`.github/workflows/build-and-deploy.yml` defines six jobs, and not all of them
run on every event. They run in parallel, so a red square names what broke
before you open the log.

| Job | Runs on |
|---|---|
| `build` | every push and every pull request |
| `infra-lint` | every push and every pull request |
| `trivy-fs` | every push and every pull request |
| `docs-check` | every push and every pull request |
| `dependency-review` | **pull requests only**. It diffs what the PR adds against the base, and a push has no base to diff against |
| `deploy` | gated off: push to `main` **and** `vars.DEPLOY_ENABLED == 'true'`, which is unset, so it reports as skipped |

A separate `codeql.yml` workflow analyses both modules on push, on pull
requests and weekly. The weekly run is there because CodeQL ships new queries,
and code that was clean when it merged can be found vulnerable months later
without a line of it changing.

| Job | Gate | Fails when |
|---|---|---|
| `build` | `maven-enforcer` | wrong JDK, wrong Maven, duplicate dependency versions, or a transitive downgrade (`requireUpperBoundDeps`) |
| `build` | JaCoCo | bundle coverage below 80% line or 50% branch |
| `build` | ArchUnit | a layering rule is broken (9 rules in `ArchitectureTest`) |
| `infra-lint` | kubeconform | the rendered `k8s/overlays/aws` is not valid against the Kubernetes 1.36 schemas |
| `infra-lint` | `cfn-lint`, `sam validate` | a CloudFormation or SAM template is malformed |
| `infra-lint` | `shellcheck` v0.11.0, `bash -n` | any tracked `*.sh` has a lint finding or a syntax error |
| `trivy-fs` | Trivy | a CRITICAL/HIGH vulnerability **with a fix available**, or a committed secret |
| `dependency-review` | dependency-review | the pull request *adds* a dependency with a high-severity advisory. The job needs the repository's dependency graph. If the graph is switched off, the job names the setting in its summary and passes, because no commit can fix a repository setting. A probe that answers anything other than 403 or 404 (an outage, a token problem) fails the job, so "the API had a bad minute" never looks like "the feature is off" |
| `docs-check` | `scripts/refcheck.py` | a backticked `path` or `path#symbol` in any Markdown file does not resolve |
| `docs-check` | `scripts/linkcheck.py` | a relative link or heading anchor is broken |
| `docs-check` | `scripts/sweeps.sh` | a co-author trailer, a signature line, or a private path or file name appears in a tracked file **or a commit message** |

Run the doc gates before you push. They are fast, and they catch real mistakes:

```bash
python3 scripts/refcheck.py && python3 scripts/linkcheck.py && scripts/sweeps.sh
```

They have already caught errors in this repository's own documentation: a
method name that did not exist, and a README count that was wrong. So they fail
CI instead of only warning.

Run the shell gate locally too, and mind the version. 0.10 and 0.11 disagree
about `cmd && log … || true`, so CI pins v0.11.0 instead of using whatever the
runner image ships. `brew install shellcheck` installs the same version today:

```bash
shellcheck $(git ls-files '*.sh')
```

Pass all the scripts in one call. The other scripts source `deploy/aws/lib.sh`,
and shellcheck only follows it when it is in the input list. Leave it out and
every script that sources it reports SC1091.

## Architecture rules

`ArchitectureTest` fails the build; it does not just write a report. The rules,
in brief:

| Rule | In brief |
|---|---|
| `layers_are_respected` | layering is respected |
| `controllers_do_not_touch_entities` | no `@Entity` in a controller |
| `no_field_injection` | no field injection |
| `no_java_util_logging` | no `java.util.logging` |
| `no_standard_streams` | nothing writes to stdout |
| `repositories_are_interfaces` | repositories are interfaces named `*Repository` |
| `transactions_are_opened_only_in_the_service_layer` | `@Transactional` appears only in `service` |
| `the_wall_clock_is_read_only_by_entities` | no `Instant.now()` outside `entity` |
| `no_web_types_below_the_controller` | no servlet types in service, entity or repository |

The `Instant.now()` rule allows the wall clock inside `entity`, and today the
only use there is `Booking.createdAt`. If you need another, discuss it in the
pull request before anyone widens the rule.

## Documentation

Documentation is part of the change and ships with it:

- Cite code as `path#symbol`, never as `path:line`. Line numbers rot on the
  next commit, and `refcheck.py` can verify symbols but not line numbers.
- A decision with a real trade-off gets an ADR in `adr/`. The format is in
  [adr/README.md](adr/README.md). Record the decision, the alternatives and
  what it costs. An ADR that only lists benefits is a brochure.
- Numbers come from `scripts/numbers.sh`.
- British spelling.
- No emoji in prose. The only exception is the three-symbol legend in the
  README's Project status table, where the symbol *is* the content. A reader
  scanning the table sees at a glance which rows are executed, which are only
  reviewed and which are absent, and three words in a narrow column do that
  worse. Anywhere else an emoji is decoration.

## Commits and pull requests

- Imperative mood, describing what the commit does: *"Bound the outbox so a
  poison row cannot block the queue"*, not *"fixed outbox"*.

- The body explains **why**, especially when the change is small and the
  reasoning is not.

- No trailers such as `Co-Authored-By`.

- One PR per concern. A refactor and a behaviour change in one diff cannot be
  reviewed properly, and cannot be reverted separately when one of them is
  wrong.

- The template asks what breaks if the change is wrong. Answer it.

## Dependabot

Dependabot runs monthly on both Maven modules, the Actions workflows and the
Dockerfile base images. `.github/dependabot.yml` sets the interval and the
grouping.

- Patch and minor updates: merge once CI is green.

- The AWS SDK BOM appears twice, in the root and in `/lambda`. **Merge both
  together**, or the two modules disagree about the SDK version.

- Major updates get their own PR and a note in the description about what was
  checked.

## Reporting a bug

Include the version (from `/actuator/info`), the `X-Request-Id` from the
response, and what you expected. Every response carries the request id,
including 401 and 403, and with it the request is one `grep` away.

Do not report a security issue in a public issue. See [SECURITY.md](SECURITY.md).
