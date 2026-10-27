# One image for every program of the project: the profile (flow, order-service) or --command picks which one runs.
#   docker run --rm --network host --env-file .env batch-integration-replace-a-file-drop:dev --command=health
FROM eclipse-temurin:21.0.5_11-jdk-jammy AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src src
COPY sql sql
RUN ./mvnw -q -B -DskipTests package

FROM eclipse-temurin:21.0.5_11-jre-jammy
RUN useradd --system --uid 10001 --create-home app
WORKDIR /app
COPY --from=build /build/target/replace-a-file-drop-0.1.0.jar app.jar
RUN mkdir -p data/inbox && chown -R app data
USER app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "app.jar"]
CMD ["--command=health"]
