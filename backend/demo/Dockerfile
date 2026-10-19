# syntax=docker/dockerfile:1

# ---- Build stage: full JDK + Gradle. Thrown away afterwards, only the jar is kept. ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

# Copy only the build files first, so the downloaded libraries become their own cached layer
# and are not downloaded again when only the source code changed.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN sh gradlew --no-daemon dependencies > /dev/null

COPY src src
RUN sh gradlew --no-daemon bootJar -x test \
    && rm -f build/libs/*-plain.jar

# ---- Runtime stage: only a JRE and the jar. ----
FROM eclipse-temurin:25-jre

# Never run the app as root inside the container.
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar

USER app
EXPOSE 8080
# MaxRAMPercentage: let the JVM use at most 60% of the container's memory limit as heap.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/app.jar"]
