FROM openjdk:17-jdk-slim

# Set working directory
WORKDIR /app

# Add metadata
LABEL maintainer="omero@yourdomain.com"
LABEL version="1.0.0"
LABEL description="Spring Client for Omero"

# Copy application
COPY target/spring-client-omero.jar .

# Expose port
EXPOSE 8080

# Set environment variables
ENV JAVA_OPTS="-Xmx256m -Xms128m"
ENV SPRING_PROFILES_ACTIVE=prod

# Start application
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "spring-client-omero.jar"]
