FROM maven:3.9-eclipse-temurin-17-alpine AS builder
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S studypilot && adduser -S studypilot -G studypilot
COPY --from=builder /workspace/target/studypilot-backend-0.0.1-SNAPSHOT.jar app.jar
USER studypilot
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
