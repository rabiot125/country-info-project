# syntax=docker/dockerfile:1.7

# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# The cache mount keeps ~/.m2 between builds, so dependencies download once.
# No `dependency:go-offline`: it pulls many plugin artifacts the build never uses,
# which made first builds slow and fragile on unreliable networks.
# The aether.* flags retry dropped downloads instead of failing the build.
ENV MAVEN_ARGS="-B -Daether.connector.http.retryHandler.count=5 -Daether.connector.requestTimeout=120000 -Daether.connector.connectTimeout=30000"

COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn $MAVEN_ARGS package -DskipTests \
 && java -Djarmode=layertools -jar target/country-info-service.jar extract --destination /layers

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre-jammy

RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
# Spring Boot layers: least- to most-frequently changed, for better image layer reuse.
COPY --from=build --chown=app:app /layers/dependencies/ ./
COPY --from=build --chown=app:app /layers/spring-boot-loader/ ./
COPY --from=build --chown=app:app /layers/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /layers/application/ ./

USER 10001:10001

# Container-aware heap; die fast on OOM so Kubernetes restarts a clean JVM.
# Writes only go to /tmp, which Kubernetes mounts as an emptyDir (read-only root FS friendly).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp -Duser.timezone=UTC -Djava.security.egd=file:/dev/./urandom" \
    SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080 8081

# Kubernetes probes are the source of truth; no HEALTHCHECK (it would need curl in the image).
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]