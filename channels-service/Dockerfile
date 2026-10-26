# docker build -f channels-service/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY auth-service/pom.xml auth-service/pom.xml
COPY channels-service/pom.xml channels-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY subscription-service/pom.xml subscription-service/pom.xml
RUN mvn -q -B -pl channels-service -am dependency:go-offline
COPY channels-service/src channels-service/src
RUN mvn -q -B -pl channels-service -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd -r -u 10001 app
WORKDIR /app
COPY --from=build /src/channels-service/target/*.jar app.jar
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
