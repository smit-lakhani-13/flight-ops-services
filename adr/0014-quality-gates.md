# 14. The build fails on architecture, coverage and dependency drift

Status: accepted (recorded 2026-09-22, decision taken in commits `631f5f0`
and `f8d2d4b`)

## Context

A rule that lives only in a README decays. "Controllers do not touch
entities", "no field injection" and "we keep coverage up" are all true on the
day they are written and unverifiable a month later. The question is which of
them can be made executable cheaply enough that no one wants to turn them off.

## Decision

I added two gates in `pom.xml`, one in the test sources, and an SBOM that the
build emits:

| Gate | What fails the build |
|---|---|
| maven-enforcer | Java outside `[21,22)`, Maven below 3.9, duplicate dependency declarations, and `requireUpperBoundDeps` (a transitive dependency resolved *lower* than something else needs) |
| JaCoCo `check` | Bundle line coverage below 0.80 or branch coverage below 0.50 |
| CycloneDX | (Not a gate; emits `target/bom.json` as a build artefact) |
| ArchUnit | Any of nine layering and hygiene rules, in `src/test/java/com/smit/flightops/ArchitectureTest.java` |

## Consequences

* **Thresholds below the measurement.** I set the gates at 80% line and 50%
  branch, well under what the build measures. Each `./mvnw verify` writes the
  current figures to `target/site/jacoco/index.html`. A threshold at the
  current number turns every ordinary refactor into a red build. People learn
  to lower it, and the gate becomes a coverage ratchet no one believes. The
  gate is there to catch a *collapse*, and a single percentage point should
  not fail it.

* `requireUpperBoundDeps` earned its place immediately. Adding springdoc pulled
  in swagger-core, which was compiled against a newer Jackson 2 than Boot
  manages. Maven's nearest-wins rule would have handed it the older one without
  a word. The enforcer turned a runtime `NoSuchMethodError` into a build failure
  with the tree printed. See [ADR 0012](0012-openapi-public-read.md).

* **No ignore lists.** Once a gate acquires a list of exceptions, it documents
  what is broken and stops being a gate. Every exemption in the ArchUnit rules
  is part of the rule's scope, with its reason in the rule's Javadoc, and
  there is no suppression file. The clock rule, for
  example, has none.

* I checked each ArchUnit rule against a planted violation before committing
  it. A rule that has never failed is a rule no one has shown to work.

* The SBOM is generated, and nothing consumes it yet. It is one command from
  being scanned, and generating it now means the first scan has history to
  compare against.

## Alternatives considered

* **SonarQube or a hosted gate.** More rules, an account, and a service to keep
  running. The three here fail locally, offline, in the same command a
  developer already runs.

* **Checkstyle/PMD.** Style rules generate the most noise per defect caught.
  The formatting in this repository is consistent because I wrote all of it,
  so a linter would mostly produce diffs.

* **A coverage ratchet (never decrease).** Sounds principled, punishes deleting
  dead code, and rewards writing tests for getters.
