FROM openjdk:17-jdk-slim

WORKDIR /app

COPY target/auth-service.jar .

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "auth-service.jar"]
