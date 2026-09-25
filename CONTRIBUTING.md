# Contributing

## Use JDK 21

**Java 21, not 25.** Spring Boot 4.1 supports 25, but the build is pinned to 21.
`maven-enforcer` stops the build on any other JDK before anything compiles, and
its message says what to set: `This build needs JDK 21. Point JAVA_HOME at a
JDK 21 (with Homebrew on macOS: export JAVA_HOME=/opt/homebrew/opt/openjdk@21).`
Without that rule, a build on 17 would fail later, in the compiler, on
`release version 21 not supported`. The upper bound is for reproducibility, not
a known failure on 25. CI, the image and the Lambda runtime all use 21
(`.github/workflows/build-and-deploy.yml`, `Dockerfile`,
`lambda/template.yaml`), so a build on 25 would be one that CI never checked.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21      # Homebrew, Apple Silicon
# export JAVA_HOME=/usr/local/opt/openjdk@21       # Homebrew, Intel
export PATH="$JAVA_HOME/bin:$PATH"
java -version                                      # must say 21
```

On macOS, set `JAVA_HOME` yourself. `/usr/libexec/java_home -v 21` finds only
the JDKs registered with macOS, and Homebrew's are not registered. When the
only JDK it knows is 17, it still exits 0 and prints the path of the 17. A
`JAVA_HOME` set from it then fails the enforcer.

## Building

`lambda/` is a separate module with no parent and its own dependency tree, so
it has its own Maven build:

```bash
./mvnw -B clean verify                       # the service
./mvnw -B -f lambda/pom.xml clean verify     # the consumer
```

The gate is `verify`. The enforcer rules bind to `validate`, so `test` runs
them too, but `test` stops before `target/bom.json` (`package`) and JaCoCo's
threshold check (`verify`).

Both modules' Surefire runs pin the test JVM to `Asia/Kolkata`. CI runs in UTC,
where a formatter that used the system zone would still pass; at +05:30 it
fails.

### Skipped tests without Docker are correct

Thirteen tests are in five classes annotated
`@Testcontainers(disabledWithoutDocker = true)`. Nine run on PostgreSQL: five
in `BookingIntegrationTest`, three in `service/OutboxPrunePostgresTest` and
one in `LockTimeoutPostgresTest`. Four run the AWS SDK code against an
emulator: one in `service/SqsEventPublisherElasticMqTest`, against ElasticMQ,
and three in the Lambda's `BookingEventHandlerDynamoDbLocalTest`, against
DynamoDB Local. Without a container runtime they skip, and a local build is
still green and still correct.

CI runs them on runners with Docker, so every CI run covers the PostgreSQL
paths that a laptop without Docker skips: Flyway with `ddl-auto=validate`,
`SELECT FOR UPDATE` under 20-way contention, a 20-thread key race, the native
`DELETE … FOR UPDATE SKIP LOCKED` under two pruners, the outbox claim under
two competing pollers, and PostgreSQL's own `lock_timeout` firing on a held
flight row. The build job's step "The PostgreSQL tests ran" fails CI if any of
the three classes skips a test or has no report.

The emulator tests check what a mocked client cannot: that ElasticMQ accepts
the message `SqsEventPublisher` sends and hands back its body and attributes
unchanged, and that DynamoDB Local accepts the item at the table's key and
refuses a redelivery through the handler's condition. The step "The emulator
tests ran" fails CI if either class skips a test or has no report. An
emulator is not AWS, and neither class talks to AWS.

So in CI the Surefire summary reads
`Tests run: 279, Failures: 0, Errors: 0, Skipped: 0` for the service and
`Tests run: 28, Failures: 0, Errors: 0, Skipped: 0` for the Lambda. On a laptop
without Docker the service line ends `Skipped: 10`, and 269 of its tests run.
The Lambda line ends `Skipped: 3`, and 25 run.

A new migration is not accepted until CI has gone green on it. The local H2
profile never sees it, and neither does a laptop with no Docker.

## Running it

```bash
./mvnw spring-boot:run       # H2 in memory, seeded, no setup
scripts/demo.sh              # the eight acts, with pauses
scripts/demo.sh --fast       # without
```

Against real PostgreSQL:

```bash
docker compose up --build
```

For anything containing `${…}`, use environment variables instead of
`-Dspring-boot.run.arguments`. The shell mangles the argument form, and the
resulting failure blames the property.

## Writing tests

- The `@WebMvcTest` slices run with `addFilters = false`. A slice does not load
  `SecurityConfig`, so with the filters on, every controller test would run
  against Boot's default chain instead of the application's. `SecurityRulesTest`
  tests the real rules once, with real credentials and the real 401 and 403
  bodies.

- The contract tests share no jar, because a shared module would make the
  producer and consumer deploy together. Both read
  `contracts/booking-created-v1.json`. The producer's `BookingEventContractTest`
  asserts set equality on the field names, so an added field fails as well as a
  removed one. The consumer's test parses the same file through the handler's
  own mapper and checks every field it depends on.

- Use `@MockitoBean`. `@MockBean` was deprecated in Boot 3.4 and removed in
  4.0.

## Tests

The table below breaks the tests in the two builds of [Building](#building)
down by layer. Every count here comes from `scripts/numbers.sh`, and its
per-class listing is what the table adds up. Do not edit a count by hand:

```bash
scripts/numbers.sh          # recomputes every count the docs claim
```

| Layer | Tests | Tooling |
|---|---|---|
| Domain entity | 13 | plain JUnit, with no Spring and no database |
| Service | 36 | `@ExtendWith(MockitoExtension.class)`, `@Mock`, `@InjectMocks`, `@Captor`, split across `BookingServiceTest` (orchestration, including a failed insert with no winning booking to recover), `BookingWriterTest` (the write path), `FlightServiceTest` and `SqsEventPublisherTest` (what goes on the wire) |
| Web slice | 75 | `@WebMvcTest` + `@MockitoBean` in the two controller tests: status codes, `Location` headers, error JSON, `Allow` on a 405 and `Accept` on a 415, the 503 for a database that cannot be reached, a YAML body or a missing `Content-Type` refused on each `POST` and `PATCH`, and the rules for flight numbers, airport codes, passenger names, seat counts, status values and departure times. The other 4 have no Spring context. 2 are `exception/ApiErrorControllerTest`: one calls `ApiErrorController` directly and one drives it through a standalone MockMvc, because a full MockMvc never forwards to `/error`. 2 are `security/JsonAccessDeniedHandlerTest`, which builds its request directly so that the path can carry a raw CR and LF |
| Repository slice | 10 | `@DataJpaTest` + `TestEntityManager`: derived queries, JPQL, `JOIN FETCH`, constraints |
| Full context (H2) | 89 | `@SpringBootTest`. The idempotency guarantee end to end, with four 10-caller races on one key: same request, different payloads, the last seat, and one key across two flights. The authorisation rules against the real filter chain, with the Basic and Bearer challenges and who sees health components. The outbox with its trace capture, the attempt ceiling and the retention pruner against an embedded database. The OpenAPI document's status codes per operation and its comparison with a real response. The lock timeout, the error contract with the 406 and `ignorecase` on a sort property that is not text, the page overflow and multipart parsing turned off, and a lazy-loading regression with no mocking anywhere in the chain |
| Event contract | 11 | the producer's and the consumer's `BookingEventContractTest`, both against `contracts/booking-created-v1.json`, as [Writing tests](#writing-tests) describes |
| Lambda handler | 19 | separate module: batch parsing, partial batch failure and the conditional write. `seats` is refused with no coercion when it is missing, below 1, a string or fractional. Body values are logged on one line and capped at 1,000 characters, and the producer's trace context survives the queue |
| Configuration and startup checks | 24 | Boot's `Binder` over plain maps: an unresolved `${...}` placeholder is rejected at startup, every outbox bound is enforced and every default is wired. `EventPropertiesTest` also starts the whole application to see a bad `app.events.publisher` named, and `PasswordVerifiabilityTest` runs `SecurityConfig` in a `WebApplicationContextRunner` to see an unverifiable password stop startup. `ValidationClockTest` checks that `@Future` reads the `Clock` bean, and `AwsConfigTest` that the `sts` module, which the credential chain needs for IRSA, is on the classpath |
| Architecture | 9 | ArchUnit over `target/classes`, one test per rule in [Architecture rules](#architecture-rules). Each rule was seen to fail on a planted violation before it was committed |
| Observability | 8 | the request-id filter against a hostile inbound header, and the booking meters scraped through a real `PrometheusMeterRegistry`, since a `SimpleMeterRegistry` would accept any name |
| Run | 294 | 0 failures without Docker (13 + 36 + 75 + 10 + 89 + 11 + 19 + 24 + 9 + 8) |
| PostgreSQL integration | 9 | `@Testcontainers(disabledWithoutDocker = true)`, skipped without a container runtime; [Skipped tests without Docker are correct](#skipped-tests-without-docker-are-correct) names the classes and what they cover |
| Emulators | 4 | `@Testcontainers(disabledWithoutDocker = true)` as well: `SqsEventPublisherElasticMqTest` sends through `SqsEventPublisher` to ElasticMQ and reads the message back, and the Lambda's `BookingEventHandlerDynamoDbLocalTest` runs the handler against DynamoDB Local. Emulators, not AWS |

So 307 tests exist across the two modules. 294 run without Docker and 13
skip, and CI runs all 307.

## What CI enforces

`.github/workflows/build-and-deploy.yml` defines seven jobs, and not all of them
run on every event. The workflow runs on every push or pull request to `main`,
and on a manual run. The jobs run in parallel, so a red square names what broke
before you open the log.

| Job | Runs on |
|---|---|
| `build` | every trigger |
| `infra-lint` | every trigger |
| `trivy-fs` | every trigger |
| `docs-check` | every trigger |
| `image` | every trigger. It builds and starts the image and never pushes it |
| `dependency-review` | **pull requests only**. It diffs what the PR adds against the base, and a push has no base to diff against |
| `deploy` | gated off: a push or manual run on `main` **and** `vars.DEPLOY_ENABLED == 'true'`. That variable is unset, so the job reports as skipped. Its display name, `deploy (gated off)`, says so in the checks list |

A separate `codeql.yml` workflow analyses both modules on every push or pull
request to `main`, weekly, and by hand. The weekly run is there because CodeQL
ships new queries, and code that was clean when it merged can be found
vulnerable months later without a line of it changing. The manual trigger is
for a commit on `main` that got no push run.

| Job | Gate | Fails when |
|---|---|---|
| `build` | `maven-enforcer` | wrong JDK, wrong Maven, duplicate dependency versions, or a transitive downgrade (`requireUpperBoundDeps`) |
| `build` | JaCoCo | bundle coverage below 80% line or 50% branch |
| `build` | ArchUnit | a layering rule is broken (9 rules in `ArchitectureTest`) |
| `build` | "The PostgreSQL tests ran" | `BookingIntegrationTest`, `service/OutboxPrunePostgresTest` or `LockTimeoutPostgresTest` has no readable report, no tests, or a skipped test |
| `build` | "The emulator tests ran" | `service/SqsEventPublisherElasticMqTest` or the Lambda's `BookingEventHandlerDynamoDbLocalTest` has no readable report, no tests, or a skipped test |
| `build` | "The SAM template points at the Lambda jar" | `lambda/template.yaml`'s `CodeUri`, which resolves against `lambda/`, is not a built file, or the jar lacks the `Handler` class |
| `build` | "Both SBOMs exist" | `target/bom.json` or `lambda/target/bom.json` is missing or empty |
| `infra-lint` | kubeconform | the rendered `deploy/k8s/overlays/aws`, `deploy/k8s/namespace.yaml` or `deploy/k8s/components/ingress/ingress.yaml` is not valid against the Kubernetes 1.36 schemas |
| `infra-lint` | `cfn-lint`, `sam validate` | a CloudFormation or SAM template is malformed |
| `infra-lint` | `shellcheck` v0.11.0, `bash -n` | any tracked `*.sh` has a lint finding or a syntax error |
| `infra-lint` | `deploy/aws/selftest.sh` | `down.sh`, `cost-check.sh`, `ecr-image-exists.sh` or `up.sh`'s checks in `lib.sh` reach a wrong verdict against stub `aws`, `kubectl`, `helm`, `eksctl`, `sleep` and `mvnw` |
| `trivy-fs` | Trivy | a CRITICAL/HIGH vulnerability **with a fix available**, or a committed secret that Trivy rates CRITICAL/HIGH |
| `dependency-review` | dependency-review | the pull request *adds* a dependency with a high-severity advisory. The job needs the repository's dependency graph. If the graph is switched off, the job names the setting in its summary and passes, because no commit can fix a repository setting. A probe that answers anything other than 200, 403 or 404 (an outage, a token problem) fails the job, so "the API had a bad minute" never looks like "the feature is off" |
| `docs-check` | `scripts/refcheck.py` | a backticked `path` or `path#symbol` in any Markdown file does not resolve |
| `docs-check` | `scripts/linkcheck.py` | a relative link or heading anchor is broken |
| `docs-check` | `scripts/numbers.sh --check-readme` | a README line that names `adr/` gives a count of records other than the number of ADR files in `adr/` (or no such line exists, or two disagree), or the index in `adr/README.md` does not link each ADR file exactly once (a missing, extra or repeated row) |
| `docs-check` | `scripts/sweeps.sh` | a co-author trailer line or an appended "Generated with" signature appears in a tracked file or in a commit message on any ref, an absolute home-directory path appears in a tracked file, a pattern from the `SWEEP_PATTERNS` secret matches a tracked file path, a file's contents or a commit message, or `SWEEP_PATTERNS` is empty on a push or a manual run |
| `image` | `docker build` | the `Dockerfile` does not build |
| `image` | "The image will not start without a database" | the image, run with no environment, does not stop with `'url' must start with` |
| `image` | Trivy, on the image | the image the job built has a CRITICAL vulnerability with a fix available |
| `deploy` | "Is this commit already in ECR?" | `describe-images` fails with anything other than `ImageNotFoundException` |
| `deploy` | "The image will not start without a database" | the image, run with no environment, does not stop with `'url' must start with` |
| `deploy` | Trivy, on the image | the built image has a CRITICAL vulnerability with a fix available. It runs before the push |
| `deploy` | rollout and smoke test | the rollout does not finish in 12 minutes, or readiness is not `UP`, or `/v3/api-docs` is not served through a port-forward |

No Trivy finding is silenced, and there is no `.trivyignore`. Trivy reads one
from the repository root if it is ever added. Each entry would carry the CVE
id, a reason a reviewer can disagree with (why the code is unreachable, or why
the risk is accepted; never just "false positive") and an expiry date. A CVE
with no released fix never needs an entry, because every scan here passes
`ignore-unfixed`; one that is inconvenient to fix needs a dependency bump.

Run the doc gates before you push. They are fast, and they catch real mistakes:

```bash
python3 scripts/refcheck.py && python3 scripts/linkcheck.py \
  && scripts/numbers.sh --check-readme && scripts/sweeps.sh
```

They have already caught errors in this repository's own documentation: a
method name that did not exist, and a README count that was wrong. That is why
they fail CI and are more than warnings.

`scripts/sweeps.sh --tree-only` skips the history walk. The home-directory
check ignores the path part of a URL. The script also reads case-insensitive
extended regular expressions from the `SWEEP_PATTERNS` environment variable,
and CI passes a repository secret of that name. When the variable is empty, a
push or a manual run fails, so a deleted secret cannot turn the check
into a silent pass. A local run or a pull request with the variable empty
prints `skip` for that part and passes, because forks and Dependabot pull
requests get no secrets.

Run the shell gate locally too, and mind the version. 0.10 and 0.11 disagree
about `cmd && log … || true`, so CI pins v0.11.0 instead of using whatever the
runner image ships. `brew install shellcheck` installs the same version today:

```bash
shellcheck $(git ls-files '*.sh')
```

Pass all the scripts in one call. `up.sh`, `down.sh` and `cost-check.sh`
source `deploy/aws/lib.sh`, and shellcheck only follows it when it is in the
input list. Leave it out and every script that sources it reports SC1091.

## Architecture rules

`ArchitectureTest` fails the build when a rule breaks. The rules, in brief:

| Rule | In brief |
|---|---|
| `layers_are_respected` | layering is respected |
| `controllers_do_not_touch_entities` | no `@Entity` in a controller |
| `no_field_injection` | no field injection |
| `no_java_util_logging` | no `java.util.logging` |
| `no_standard_streams` | nothing writes to stdout |
| `repositories_are_interfaces` | repositories are interfaces named `*Repository` |
| `transactions_are_opened_only_in_the_service_layer` | `@Transactional` appears only in `service` |
| `time_comes_from_the_clock` | in main code, no `java.time` `now()` without a `Clock`, no `System.currentTimeMillis()`, no `new Date()` and no `Calendar.getInstance()` |
| `no_web_types_below_the_controller` | no servlet types in service, entity or repository |

Every class takes time from the injected `Clock`. If you need an exemption,
discuss it in the pull request before anyone widens the rule.

## Documentation

Documentation is part of the change and ships with it:

- Cite code as `path#symbol`, never as `path:line`. Line numbers rot on the
  next commit, and `refcheck.py` can verify symbols but not line numbers. It
  checks a backticked span that ends in a known extension or names
  `Dockerfile`, `mvnw` or `LICENSE`. A bare filename or a package-relative
  path, such as `config/SecurityConfig.java`, resolves when exactly one
  tracked file matches it, and one that names a file at the repository root
  resolves to that file. A bare filename that matches more than one tracked
  file and none at the root is accepted without its `#symbol` being checked,
  so cite a longer path when the name is shared. The script itself lists what
  it skips.
- A decision with a real trade-off gets an ADR in `adr/`. The format is in
  [adr/README.md](adr/README.md). Record the decision, the alternatives and
  what it costs.
- Numbers come from `scripts/numbers.sh`. Run it without a flag before you
  edit a count. CI compares only the ADR count with the documents, so any
  other stale count still passes.
- British spelling.
- No emoji in prose.

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

## Versions

| Component | Version | Set by |
|---|---|---|
| Java | 21 (21.0.12.1 in the local builds) | `<java.version>` in `pom.xml` and `<maven.compiler.release>` in `lambda/pom.xml`. CI asks `setup-java` for Temurin 21 with no patch release |
| Jakarta EE | 11 | Spring Boot (Servlet 6.1, Persistence 3.2, Validation 3.1) |
| Spring Boot | 4.1.1 | the parent in `pom.xml` |
| Spring Framework | 7.0.9 | Spring Boot |
| Spring Security | 7.1.1 | Spring Boot |
| Tomcat | 11.0.26 | `<tomcat.version>` in `pom.xml`, over Boot's 11.0.24 |
| Hibernate | 7.4.5 | Spring Boot |
| Jackson 3 | 3.1.5 | Spring Boot |
| Jackson 2 | 2.22.2 | `<jackson-2-bom.version>` in `pom.xml`, over Boot's 2.21.5; `<jackson.version>` in `lambda/pom.xml` |
| Flyway | 12.4.0 | Spring Boot |
| springdoc-openapi | 3.1.1 | `<springdoc.version>` in `pom.xml` |
| AWS SDK for Java | 2.55.2 | `<aws.sdk.version>` in both POMs |
| JUnit | 6.0.3 | Spring Boot in the service; `<junit.version>` in `lambda/pom.xml` |
| Maven | 3.9.16 | `.mvn/wrapper/maven-wrapper.properties` |

`pom.xml` changes one dependency version that Boot manages: it sets
`jackson-2-bom.version` to 2.22.2. Boot 4 runs on Jackson 3 and still manages
the Jackson 2 coordinates at 2.21.5 for libraries that have not moved. The
OpenAPI document is built by swagger-core, which is one of them and needs at
least 2.22.1. Boot's dependency management would have handed it the older
Jackson 2 with no error, and the enforcer's `requireUpperBoundDeps` rule
refused the build instead. Setting Boot's own property moves the whole
Jackson 2 line together, and `lambda/pom.xml` keeps the same version, so the
repository has one Jackson 2 to patch. The enforcer and CycloneDX plugin pins
in `pom.xml` match the versions Boot manages today; they are there so that a
Boot upgrade does not move them.

`./mvnw` pins Maven 3.9.16 and its SHA-256, so CI needs no Maven install step
and a substituted archive fails the build. The wrapper is
`distributionType=only-script`: two scripts, `mvnw` and `mvnw.cmd`, and a
properties file, with no `maven-wrapper.jar` committed.

What the Boot 3.5 to 4.1 upgrade broke is in
[the defect log](doc/DEFECT-LOG.md#the-boot-4-upgrade), and why the service
moved is in [ADR 0007](adr/0007-spring-boot-4.md). Dependabot proposes
version bumps, and [Dependabot](#dependabot) says what it leaves to a person
and how to handle its pull requests.

## Dependabot

Dependabot runs monthly on both Maven modules, the Actions workflows and the
Dockerfile base images. `.github/dependabot.yml` sets the interval and the
grouping. The Lambda module has its own entry, because with no parent POM
nothing else manages its versions.

- `open-pull-requests-limit` is set on every entry (3, 2, 1 and 2), because the
  default is 5 per entry. The four entries at the default can open twenty pull
  requests the first time Dependabot runs.

- The `ignore` rules cover five artifacts and no more, because an `ignore` also
  suppresses Dependabot's security updates for that dependency. That is accepted
  only where a bump would contradict a pin the project documents:
  `eclipse-temurin` and `maven` in the two base images (majors),
  `org.springframework.boot:spring-boot-starter-parent` (majors), and
  `org.junit:junit-bom` and `org.testcontainers:*` under `/lambda`.

- The `junit-bom` and Testcontainers ignores cover majors, minors and patches.
  `<junit.version>` and `<testcontainers.version>` in `lambda/pom.xml` copy
  what Boot's BOM gives the service module, and whichever side moves first
  splits the repository across two versions.

- Patch and minor updates: merge once CI is green.

- The AWS SDK BOM appears twice, in the root and in `/lambda`. **Merge both
  together**, or the two modules disagree about the SDK version.

- Major updates get their own PR and a note in the description about what was
  checked.

## Reporting a bug

Include the version (from `/actuator/info`), the `X-Request-Id` from the
response, and what you expected. Every response the application handles
carries the request id, 401 and 403 included, and with it the request is one
`grep` away. Tomcat's own 400 page and a `TRACE` refusal carry none.

Do not report a security issue in a public issue. See
[SECURITY.md](SECURITY.md).
