# docker build -f auth-service/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY auth-service/pom.xml auth-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY subscription-service/pom.xml subscription-service/pom.xml
RUN mvn -q -B -pl auth-service -am dependency:go-offline
COPY auth-service/src auth-service/src
RUN mvn -q -B -pl auth-service -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd -r -u 10001 app
WORKDIR /app
COPY --from=build /src/auth-service/target/*.jar app.jar
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
