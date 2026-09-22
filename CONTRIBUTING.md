# Contributing

## The one thing that will trip you up

**Java 21, not 25.** Spring Boot 4.1 supports 25, but the build is pinned to 21
and `maven-enforcer` fails the build on anything else — deliberately, with a
message naming the version it found:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21      # macOS, Homebrew
export PATH="$JAVA_HOME/bin:$PATH"
java -version                                      # must say 21
```

Without it the first build fails inside `maven-enforcer-plugin` and the error
is about a version range, which is not obviously about your `JAVA_HOME`.

## Building

Two Maven builds, because `lambda/` is a separate parentless module with its
own dependency tree:

```bash
./mvnw -B clean verify                       # the service
./mvnw -B -f lambda/pom.xml clean verify     # the consumer
```

`verify` is the gate, not `test`: it is what runs JaCoCo's threshold check, the
enforcer rules and the CycloneDX SBOM.

### `Skipped: 7` is correct

Seven tests sit in two classes annotated
`@Testcontainers(disabledWithoutDocker = true)` — `BookingIntegrationTest` and
`service/OutboxPrunePostgresTest`. Without a container runtime they skip; the
build is still green and still correct. In CI they run, which is where the
Flyway migrations and the outbox's native SQL are actually exercised against
PostgreSQL. **A new migration is not accepted until CI has gone green on it** —
the local H2 profile never sees it, and neither does a laptop with no Docker.

Expected today: 204 declared / 197 executed in the service, 23 in the Lambda.
Do not hand-edit those numbers anywhere:

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

Use environment variables rather than `-Dspring-boot.run.arguments` for
anything containing `${…}` — the argument form gets mangled by the shell and
the resulting failure blames the property.

## What CI enforces

Six jobs are defined in `.github/workflows/build-and-deploy.yml`, and they do
not all run on every event. They run in parallel, so a red square names what
broke before you open the log.

| Job | Runs on |
|---|---|
| `build` | every push and every pull request |
| `infra-lint` | every push and every pull request |
| `trivy-fs` | every push and every pull request |
| `docs-check` | every push and every pull request |
| `dependency-review` | **pull requests only** — it diffs what the PR adds against the base, and a push has no base to diff against |
| `deploy` | gated off: push to `main` **and** `vars.DEPLOY_ENABLED == 'true'`, which is unset, so it reports as skipped |

A separate `codeql.yml` runs on push, on pull requests and weekly.

| Job | Gate | Fails when |
|---|---|---|
| `build` | `maven-enforcer` | wrong JDK, wrong Maven, duplicate dependency versions, or a transitive downgrade (`requireUpperBoundDeps`) |
| `build` | JaCoCo | bundle coverage below 80% line or 50% branch |
| `build` | ArchUnit | a layering rule broken — 9 rules in `ArchitectureTest` |
| `infra-lint` | kubeconform | the rendered `k8s/overlays/aws` is not valid against the Kubernetes 1.36 schemas |
| `infra-lint` | `cfn-lint`, `sam validate` | a CloudFormation or SAM template is malformed |
| `infra-lint` | `shellcheck` v0.11.0, `bash -n` | any tracked `*.sh` has a lint finding or a syntax error |
| `trivy-fs` | Trivy | a CRITICAL/HIGH vulnerability **with a fix available**, or a committed secret |
| `dependency-review` | dependency-review | the pull request *adds* a dependency with a high-severity advisory. Needs the repository's dependency graph; if that is switched off the job writes a job summary naming the setting and passes, rather than going red over a repository setting nobody can fix in a commit. A probe answering anything other than 403 or 404 — an outage, a token problem — fails the job instead, because "the API had a bad minute" and "the feature is off" must not look the same |
| `docs-check` | `scripts/refcheck.py` | a backticked `path` or `path#symbol` in any Markdown file does not resolve |
| `docs-check` | `scripts/linkcheck.py` | a relative link or heading anchor is broken |
| `docs-check` | `scripts/sweeps.sh` | a co-author trailer, a generated-with signature, or a reference to the private sibling directory, in a file **or a commit message** |

The `codeql` workflow analyses both modules on push, on pull requests and
weekly. It is scheduled as well as triggered because CodeQL ships new queries —
code that was clean when it merged can be found vulnerable months later without
a line of it changing.

Run the doc gates before pushing; they are fast and they catch real mistakes:

```bash
python3 scripts/refcheck.py && python3 scripts/linkcheck.py && scripts/sweeps.sh
```

The shell gate is worth matching locally too, and the version matters — 0.10 and
0.11 disagree about `cmd && log … || true`, so CI pins v0.11.0 rather than using
whatever the runner image happens to ship. `brew install shellcheck` gives the
same one today:

```bash
shellcheck $(git ls-files '*.sh')
```

All the scripts in one invocation, not one per file: `deploy/aws/lib.sh` is
sourced by the others, and shellcheck only follows it when it is in the input
list. Leave it out and every script that sources it reports SC1091.

Both have already caught errors in this repository's own documentation — a
method name that did not exist, and a README count that was wrong — which is
why they are gates rather than suggestions.

## Architecture rules

`ArchitectureTest` fails the build rather than producing a report. The nine
rules, in brief: layering is respected, no `@Entity` in a controller, no field
injection, no `java.util.logging`, nothing writes to stdout, repositories are
interfaces named `*Repository`, `@Transactional` appears only in `service`, no
`Instant.now()` outside `entity`, and no servlet types in service, entity or
repository.

The `Instant.now()` rule has one documented exemption, `Booking.createdAt`. If
you need another, the discussion belongs in the pull request, not in a widened
rule.

## Documentation

Documentation is part of the change, not a follow-up:

- Cite code as `path#symbol`, never as `path:line`. Line numbers rot on the
  next commit; `refcheck.py` verifies symbols and cannot verify line numbers.
- A decision with a trade-off worth arguing about gets an ADR in `adr/`. The
  format is in [adr/README.md](adr/README.md). Record the decision, the
  alternatives, and what it costs — an ADR that only lists benefits is a
  brochure.
- Numbers come from `scripts/numbers.sh`.
- British spelling.
- No emoji in prose, with exactly one exception: the three-symbol legend in the
  README's Project status table. There, the symbol *is* the content — a reader
  scanning the table needs to see at a glance which rows are executed, which are
  only reviewed, and which are absent, and three words in a narrow column do
  that worse than three symbols. Anywhere else an emoji is decoration, and
  decoration in a document that makes checkable claims reads as a substitute for
  one.

## Commits and pull requests

- Imperative mood, describing what the commit does: *"Bound the outbox so a
  poison row cannot block the queue"*, not *"fixed outbox"*.
- The body explains **why**, especially when the change is small and the
  reasoning is not.
- No trailers. No `Co-Authored-By`, no tool attribution, no generated-by lines.
- One PR per concern. A refactor and a behaviour change in one diff cannot be
  reviewed, and cannot be reverted separately when one of them is wrong.
- The template asks what breaks if the change is wrong. Answer it.

## Dependabot

Monthly, on both Maven modules, the Actions workflows and the Dockerfile base
images — `.github/dependabot.yml` sets the interval and the grouping.

- Patch and minor updates: merge once CI is green.
- The AWS SDK BOM appears twice — root and `/lambda`. **Merge both together**,
  or the modules disagree about the SDK version and the contract tests compile
  against different serialisers.
- Major updates get their own PR and a note in the description about what was
  checked.

## Reporting a bug

Include the version (`/actuator/info`), the `X-Request-Id` from the response —
it is on every response including 401 and 403 — and what you expected. With the
request id, the exact request is one `grep` away.

Security issues do not go in an issue. See [SECURITY.md](SECURITY.md).
