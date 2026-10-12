FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src src
COPY tests/fixtures.json src/main/resources/fixtures.json
RUN mvn -q -DskipTests package
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache wget
COPY --from=build /build/target/claims.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
