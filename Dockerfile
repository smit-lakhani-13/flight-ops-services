# syntax=docker/dockerfile:1
# =============================================================================
# Multi-stage build.
#
# Stage 1 needs Maven, the full JDK and the source. Stage 2 needs a JRE and one
# JAR. No size figures are quoted for either: this image has never been built
# on this machine (see the Project status table in README.md), so any number
# here would be invented.
#
# The point is not just size. The runtime image contains no compiler, no build
# tooling and no source code, so none of that is available to an attacker who
# gets a shell in the container.
# =============================================================================

# ---------- Stage 1: build ----------
# `mvn` here, `./mvnw` everywhere else. That is not an inconsistency: this base
# image already pins Maven 3.9 alongside JDK 21, which is the job the wrapper
# does elsewhere. Copying the wrapper in would download a second copy of the same
# Maven on every cache miss. The image tag is the pin at this layer.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy the pom alone first. Docker caches layers, so dependencies are only
# re-downloaded when pom.xml changes — not on every source edit.
#
# The cache mount is what makes this reliable rather than merely fast.
# dependency:go-offline does not resolve every plugin the later phases need, so
# without it the package step still reaches the network on a "cached" build and
# fails in an air-gapped or rate-limited one. The mount is a persistent
# BuildKit volume, shared across builds and never baked into a layer, so the
# second build resolves nothing at all.
#
# It requires BuildKit, which is the default in Docker 23+ and in `docker
# buildx build`. The syntax directive at the top of this file pins the
# Dockerfile frontend that understands it.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -B

COPY src ./src
# Tests run in CI, not in the image build. Building the image is not the place
# to discover a failing test — the pipeline already gated on `mvn verify`.
RUN --mount=type=cache,target=/root/.m2 mvn clean package -DskipTests -B

# ---------- Stage 2: runtime ----------
# Pinned to a major version, never :latest. In a real pipeline this would be
# pinned by digest (@sha256:...) so the base image cannot change underneath a
# rebuild of the same commit.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Passed by CI as --build-arg GIT_SHA=$GITHUB_SHA. Defaulted rather than
# required so a local build still works; "unknown" in a deployed image means
# somebody built it by hand.
ARG GIT_SHA=unknown

# OCI labels, and the one that earns its place is org.opencontainers.image.revision.
#
# The image tag is the commit SHA, which is how CI names it — but a tag is
# mutable metadata that lives in the registry, and `docker inspect` on a running
# container 3,000 miles away tells you what the tag was at pull time, not what
# is inside. A label is baked into the image config and travels with the
# content. When the question is "which commit is actually serving production",
# this is the answer that cannot have drifted:
#   docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' <image>
LABEL org.opencontainers.image.title="flight-ops-service" \
      org.opencontainers.image.description="Flight operations API: bookings, seat inventory, transactional outbox" \
      org.opencontainers.image.source="https://github.com/smit-lakhani-13/flight-ops-services" \
      org.opencontainers.image.revision="$GIT_SHA" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.base.name="eclipse-temurin:21-jre-alpine"

# Non-root, with an EXPLICIT NUMERIC UID.
#
# This matters more than it looks. The pod spec sets `runAsNonRoot: true`, and
# the kubelet enforces that by reading the UID out of the image config. If the
# image declares a *name* (USER spring), the kubelet cannot resolve it to a UID
# — it has no view of the image's /etc/passwd — and the pod fails to start with
#   Error: container has runAsNonRoot and image has non-numeric user (spring),
#   cannot verify user is non-root
# So the numeric form below is what makes `runAsNonRoot` actually work.
RUN addgroup -g 1001 -S spring && adduser -u 1001 -S spring -G spring

COPY --from=build /app/target/*.jar app.jar

USER 1001:1001

EXPOSE 8080

# Container-aware heap sizing. MaxRAMPercentage is relative to the container's
# memory limit, so the heap tracks whatever k8s grants. A fixed -Xmx equal to
# the limit leaves no room for metaspace, thread stacks, code cache or direct
# buffers, and the container gets OOMKilled.
#
# ExitOnOutOfMemoryError: a JVM that has exhausted the heap is not going to
# recover. Better to die and let the Deployment replace the pod than to sit
# there thrashing GC while readiness still passes.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

# `exec` is not optional here.
#
# Without it, `sh` stays PID 1 and the JVM is its child. Kubernetes sends
# SIGTERM to PID 1 only, so the shell absorbs it, the JVM never hears it, and
# Spring's graceful shutdown never runs — every rolling update would kill
# in-flight requests after terminationGracePeriodSeconds and look like a
# random 502. `exec` replaces the shell with the JVM, so the JVM *is* PID 1
# and receives the signal.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

# No HEALTHCHECK, deliberately. Kubernetes ignores it entirely — the three
# probes in k8s/base/deployment.yaml are what decide whether this container is
# alive, ready and started, and a HEALTHCHECK here would be a second definition
# that nothing reads and nobody updates. `compose.yaml` defines one for the
# local case, where there is no kubelet to do it.
