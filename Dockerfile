# syntax=docker/dockerfile:1
#
# D27 -- multi-stage, layered container image for the springai-med-qa backend.
#
# Stage 1 (builder): a full JDK 17 image compiles the app with the pinned Maven
#   Wrapper. Dependencies are resolved in their own cached layer (only the
#   wrapper + pom are copied first) so an application-only source change does not
#   re-download the whole Maven world on every build.
# Stage 2 (runtime): a slim JRE 17 image. The executable jar is exploded into
#   Spring Boot layers (jarmode=tools) and copied slow-changing -> fast-changing,
#   so a code edit only invalidates the tiny `application` layer while the large
#   dependency layer stays cached. The service runs as an unprivileged user with
#   container-aware JVM ergonomics.
#
# Unit tests run in CI (D5, `mvn verify`); the image build skips them
# (-DskipTests) to keep the container pipeline fast and deterministic.

##############################
# Stage 1: build + layer extraction
##############################
FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /workspace

# 1) Dependency cache layer: copy ONLY the wrapper and the build descriptor, then
#    warm the local Maven repository. Docker reuses this layer until pom.xml
#    changes, so ordinary code edits never re-resolve dependencies.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

# 2) Build the executable, layered jar. Sources are copied AFTER the go-offline
#    step above so the expensive dependency layer stays cached across code edits.
COPY src/ src/
RUN ./mvnw -B -ntp clean package -DskipTests

# 3) Explode the layered jar with the Spring Boot 3.x tools jarmode into per-layer
#    directories: dependencies/ spring-boot-loader/ snapshot-dependencies/ application/
RUN cp target/*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

##############################
# Stage 2: minimal JRE runtime
##############################
FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

# Never run the medical backend as root: create a dedicated system user/group.
RUN groupadd --system medqa \
    && useradd --system --gid medqa --home-dir /app --shell /usr/sbin/nologin medqa

# Copy the four Spring Boot layers least-changed -> most-changed. This ordering
# gives the highest Docker cache hit rate: only `application` changes per build.
COPY --from=builder --chown=medqa:medqa /workspace/extracted/dependencies/ ./
COPY --from=builder --chown=medqa:medqa /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=medqa:medqa /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=medqa:medqa /workspace/extracted/application/ ./

USER medqa
EXPOSE 8080

# Container-aware ergonomics: honour cgroup memory limits, fail fast on OOM, and
# seed the RNG from urandom so startup is not blocked on entropy.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
ENV SPRING_PROFILES_ACTIVE="prod"

# JarLauncher boots the exploded layout (Spring Boot 3.2+ loader package). `sh -c`
# lets JAVA_OPTS expand; `exec` hands PID 1 to the JVM for correct signal handling.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
