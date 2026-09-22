# 14. The build fails on architecture, coverage and dependency drift

Status: accepted (recorded 2026-09-22, decision taken in commits `631f5f0`
and `f8d2d4b`)

## Context

Every rule that lives only in a README is a rule that decays. "Controllers do
not touch entities", "no field injection", "we keep coverage up" are all true
on the day they are written and unverifiable a month later. The question is
which of them can be made executable cheaply enough that nobody wants to turn
them off.

## Decision

Four gates in `pom.xml`, plus one in the test sources:

| Gate | What fails the build |
|---|---|
| maven-enforcer | Java outside `[21,22)`, Maven below 3.9, duplicate dependency declarations, and `requireUpperBoundDeps` — a transitive dependency resolved *lower* than something else needs |
| JaCoCo `check` | Bundle line coverage below 0.80 or branch coverage below 0.50 |
| CycloneDX | (Not a gate; emits `target/bom.json` as a build artefact) |
| ArchUnit | Any of nine layering and hygiene rules, in `src/test/java/com/smit/flightops/ArchitectureTest.java` |

## Consequences

* **The thresholds are set below the measured value on purpose.** Measured
  coverage is roughly 90% line and 70% branch; the gates sit at 80 and 50.
  A threshold set at the current number turns every honest refactor into a red
  build and teaches people to lower it, which is how a coverage gate becomes a
  coverage ratchet nobody believes. The gate exists to catch a *collapse*, not
  to police a percentage point.
* `requireUpperBoundDeps` earned its place immediately: adding springdoc pulled
  swagger-core, which was compiled against a newer Jackson 2 than Boot manages,
  and Maven's nearest-wins rule would have handed it the older one silently.
  The enforcer turned a runtime `NoSuchMethodError` into a build failure with
  the tree printed. See [ADR 0012](0012-openapi-public-read.md).
* **No ignore lists.** The moment a gate acquires a list of exceptions it
  becomes documentation of what is broken rather than a gate. The one
  exemption in the ArchUnit rules —
  `src/main/java/com/smit/flightops/entity/Booking.java` reading the wall clock
  — is written into the rule with its reason, not into a suppression file.
* Each ArchUnit rule was checked against a deliberate violation before being
  committed. A rule that has never failed is a rule nobody has proved works.
* The SBOM is generated but not yet consumed by anything. It is one command
  from being scanned, and generating it now means the first scan has history
  to compare against.

## Alternatives considered

* **SonarQube or a hosted quality gate.** More rules, an account, and a service
  to keep running. The four here fail locally, offline, in the same command a
  developer already runs.
* **Checkstyle/PMD.** Style rules generate the most noise per defect caught.
  The formatting in this repository is consistent because one person wrote it;
  a linter would mostly produce diffs.
* **A coverage ratchet (never decrease).** Sounds principled, punishes deleting
  dead code, and rewards writing tests for getters.
