# syntax=docker/dockerfile:1.7
# Builds either Java application from the multi-module reactor. Build context: repository root.
#   docker build -f infra/docker/java-app.Dockerfile --build-arg APP_MODULE=control-plane .

FROM eclipse-temurin:25-jdk-noble AS build
ARG APP_MODULE
WORKDIR /src

# Copy build descriptors first so dependency resolution stays cached until a pom changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY apps/control-plane/pom.xml apps/control-plane/pom.xml
COPY apps/worker/pom.xml apps/worker/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl apps/${APP_MODULE} -am dependency:go-offline

COPY apps apps
# Tests run in CI and in ./mvnw verify; the image build only packages.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl apps/${APP_MODULE} -am -DskipTests -Dspotless.check.skip=true package \
 && cp apps/${APP_MODULE}/target/${APP_MODULE}-*.jar /app.jar \
 && java -Djarmode=tools -jar /app.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S quantarun && adduser -S -G quantarun quantarun
WORKDIR /app
# Layers ordered from least to most frequently changing, so rebuilds only replace the application layer.
COPY --from=build --chown=quantarun:quantarun /extracted/dependencies/ ./
COPY --from=build --chown=quantarun:quantarun /extracted/spring-boot-loader/ ./
COPY --from=build --chown=quantarun:quantarun /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=quantarun:quantarun /extracted/application/ ./
USER quantarun
# MaxRAMPercentage makes the heap follow the container memory limit instead of the host's RAM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
