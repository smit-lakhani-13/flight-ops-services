# =============================================================================
# Multi-stage build.
#
# Stage 1 needs Maven, the full JDK and the source: ~800MB.
# Stage 2 needs a JRE and one JAR: ~180MB.
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
FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /app

# Copy the pom alone first. Docker caches layers, so dependencies are only
# re-downloaded when pom.xml changes — not on every source edit.
#
# Caveat worth knowing: dependency:go-offline does not always resolve every
# plugin the later phases need, so the build stage can still touch the network.
# A BuildKit cache mount (--mount=type=cache,target=/root/.m2) is the robust
# fix; this form is kept because it works on any Docker version.
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
# Tests run in CI, not in the image build. Building the image is not the place
# to discover a failing test — the pipeline already gated on `mvn verify`.
RUN mvn clean package -DskipTests -B

# ---------- Stage 2: runtime ----------
# Pinned to a major version, never :latest. In a real pipeline this would be
# pinned by digest (@sha256:...) so the base image cannot change underneath a
# rebuild of the same commit.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

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
