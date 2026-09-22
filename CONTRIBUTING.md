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

### `Skipped: 5` is correct

Five tests are `@EnabledIfSystemProperty`/Testcontainers tests that need Docker.
Without Docker they skip; the build is still green and still correct. In CI they
run, which is where the Flyway migrations are actually exercised against
PostgreSQL. **A new migration is not accepted until CI has gone green on it** —
the local H2 profile never sees it.

Expected today: 179 declared / 174 executed in the service, 20 in the Lambda.
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

A change that passes locally and fails CI is almost always one of these:

| Gate | Fails when |
|---|---|
| `maven-enforcer` | wrong JDK, wrong Maven, duplicate dependency versions, or a transitive downgrade (`requireUpperBoundDeps`) |
| JaCoCo | bundle coverage below 80% line or 50% branch |
| ArchUnit | a layering rule broken — 9 rules in `ArchitectureTest` |
| `scripts/refcheck.py` | a backticked `path` or `path#symbol` in any Markdown file does not resolve |
| `scripts/linkcheck.py` | a relative link or heading anchor is broken |
| Trivy | a CRITICAL/HIGH vulnerability with a fix available |
| `scripts/sweeps.sh` | a co-author trailer, a generated-with signature, or a reference to the private sibling directory, in a file **or a commit message** |

Run the doc gates before pushing; they are fast and they catch real mistakes:

```bash
python3 scripts/refcheck.py && python3 scripts/linkcheck.py && scripts/sweeps.sh
```

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
- No emoji in documentation. British spelling.

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

Weekly, on both Maven modules and the Actions workflows.

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
