# Stage 1: Build the application using Maven
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app

# Copy the entire project context
COPY . .

# Build the application
ARG MODULE_NAME=xamops-service
RUN mvn clean package -pl ${MODULE_NAME} -am -DskipTests

# --- KEY FIX: Move the built JAR to a standard location ---
# This ensures the next stage can find it regardless of the MODULE_NAME
RUN cp ${MODULE_NAME}/target/*.jar /app/app.jar

# Stage 2: Create the final, lightweight runtime image
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Install Docker CLI
RUN apt-get update && \
    apt-get install -y docker.io && \
    rm -rf /var/lib/apt/lists/*

# The port the application will run on
ARG EXPOSE_PORT=8080
EXPOSE ${EXPOSE_PORT}

# --- KEY FIX: Copy from the standardized location ---
COPY --from=build /app/app.jar app.jar

# The command to run the application
ENTRYPOINT ["java", "-jar", "app.jar"]

# Health check
HEALTHCHECK --interval=30s --timeout=30s --start-period=5s --retries=3 CMD wget -q -O - http://localhost:8080/actuator/health | grep UP || exit 1