## What this changes

<!-- One or two sentences. What is different after this merges. -->

## Why

<!-- The reasoning, especially if the diff is small and the decision is not
     obvious. If this fixes a bug, say what the bug actually did. -->

## What breaks if this is wrong

<!-- The most useful line in this template. What is the failure mode, who
     notices, and how quickly. "Nothing — it is a doc change" is a fine
     answer. -->

## Checks

- [ ] `./mvnw -B clean verify` passes (and `-f lambda/pom.xml` if the Lambda changed)
- [ ] `python3 scripts/refcheck.py && python3 scripts/linkcheck.py && scripts/sweeps.sh` pass
- [ ] Counts in the README match `scripts/numbers.sh`
- [ ] A decision with a real trade-off has an ADR in `adr/`

## Anything to verify by hand

<!-- Anything CI cannot check: a manifest that was rendered but not applied,
     a cost implication, a migration that only runs in CI. -->
