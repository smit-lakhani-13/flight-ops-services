# syntax=docker/dockerfile:1
# Stage 1 has Maven, the full JDK and the source. Stage 2 has a JRE and one
# jar, so a shell in the running container finds no compiler or source.

# ---------- Stage 1: build ----------
# `mvn`, not `./mvnw`: this base image already pins Maven 3.9 beside JDK 21,
# which is the wrapper's job elsewhere. The image tag is the pin at this layer.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# The pom first, so dependencies download again only when pom.xml changes. The
# BuildKit cache mount is kept across builds and never baked into a layer.
# go-offline misses some plugins, so without it a cached build still downloads.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -B

COPY src ./src
# Tests run in CI's build job. The deploy job, the only one that pushes this
# image, needs that job to pass first.
RUN --mount=type=cache,target=/root/.m2 mvn clean package -DskipTests -B

# ---------- Stage 2: runtime ----------
# A major version, never :latest. A production pipeline would pin the digest
# (@sha256:...), so a rebuild of the same commit gets the same base.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# CI passes --build-arg GIT_SHA=$GITHUB_SHA. The default keeps a local build
# working; "unknown" in a deployed image means someone built it by hand.
ARG GIT_SHA=unknown

# A tag is registry metadata; a label is in the image config and travels with
# the content. To see which commit a running image was built from:
#   docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' <image>
LABEL org.opencontainers.image.title="flight-ops-service" \
      org.opencontainers.image.description="Flight operations API: bookings, seat inventory, transactional outbox" \
      org.opencontainers.image.source="https://github.com/smit-lakhani-13/flight-ops-services" \
      org.opencontainers.image.revision="$GIT_SHA" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.base.name="eclipse-temurin:21-jre-alpine"

# A numeric UID. The pod's `runAsNonRoot: true` is checked against its
# `runAsUser: 1001`. A pod without runAsUser is checked against the image's
# USER, and the kubelet does not read /etc/passwd, so a user name fails with
#   Error: container has runAsNonRoot and image has non-numeric user (spring),
#   cannot verify user is non-root
RUN addgroup -g 1001 -S spring && adduser -u 1001 -S spring -G spring

COPY --from=build /app/target/*.jar app.jar

USER 1001:1001

EXPOSE 8080

# The heap is sized from the memory limit. A fixed -Xmx equal to the limit
# leaves nothing for metaspace, thread stacks or buffers, and ends in an
# OOMKill. Half, because on a laptop, on the default profile, this JVM
# committed about 260 MiB outside the heap (deploy/k8s/base/deployment.yaml
# has the measurement). After an OutOfMemoryError the JVM exits and the pod
# is replaced.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

# prod unless told otherwise. The default profile is in-memory H2 with {noop}
# dev passwords; under prod, a container with no DB_URL stops at startup. The
# ConfigMap sets prod as well, and compose.yaml selects postgres.
ENV SPRING_PROFILES_ACTIVE=prod

# `exec` makes the JVM PID 1. Kubernetes sends SIGTERM to PID 1 only, so under
# a plain `sh -c` the shell takes the signal, Spring's graceful shutdown never
# runs, and a rolling update kills in-flight requests.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

# No HEALTHCHECK: Kubernetes ignores it and uses the three probes in
# deploy/k8s/base/deployment.yaml. compose.yaml defines one for the local case.
