# syntax=docker/dockerfile:1.7

# ── Stage 1: build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Dependencies resolve in their own layer so a source-only change does not
# re-download the world.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN --mount=type=cache,id=gradle-user-home,target=/root/.gradle,sharing=locked \
    ./gradlew dependencies --no-daemon --quiet

COPY src ./src
RUN --mount=type=cache,id=gradle-user-home,target=/root/.gradle,sharing=locked \
    ./gradlew bootJar --no-daemon -x test && \
    java -Djarmode=tools -jar build/libs/*.jar extract --layers --launcher --destination build/extracted

# ── Stage 2: runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# git is here for one reason: the publisher shells out to `git apply --check` as
# the final gate before a branch is created. JGit does the branch/commit/push,
# but the reference patch applier is the one that decides whether a diff is real.
RUN apk add --no-cache ca-certificates curl git && \
    addgroup -S nightshift && adduser -S nightshift -G nightshift && \
    mkdir -p /app/logs /app/workspace && \
    chown -R nightshift:nightshift /app

USER nightshift

COPY --from=builder /workspace/build/extracted/dependencies/ ./
COPY --from=builder /workspace/build/extracted/spring-boot-loader/ ./
COPY --from=builder /workspace/build/extracted/snapshot-dependencies/ ./
COPY --from=builder /workspace/build/extracted/application/ ./

# Where the log folder is mounted read-only, and where target repos are cloned.
ENV NIGHTSHIFT_LOG_ROOT=/app/logs \
    NIGHTSHIFT_WORKSPACE=/app/workspace \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

EXPOSE 8080 9091

HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
    CMD curl -fsS http://localhost:9091/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} org.springframework.boot.loader.launch.JarLauncher"]
