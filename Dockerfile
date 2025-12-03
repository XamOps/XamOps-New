# Stage 1: Build the application using Maven
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app

# Copy the entire project context
COPY . .

# Build the application
ARG MODULE_NAME=xamops-service
RUN mvn clean package -pl ${MODULE_NAME} -am -DskipTests

# Stage 2: Create the final, lightweight runtime image
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# --- CRITICAL FIX: Install Docker CLI HERE (Before Copy/Entrypoint) ---
RUN apt-get update && \
    apt-get install -y docker.io && \
    rm -rf /var/lib/apt/lists/*

# The port the application will run on
ARG EXPOSE_PORT=8080
EXPOSE ${EXPOSE_PORT}

# Copy only the built JAR file (Hardcoded path to avoid build-arg errors)
COPY --from=build /app/xamops-service/target/*.jar app.jar

# The command to run the application
ENTRYPOINT ["java", "-jar", "app.jar"]

# Health check
HEALTHCHECK --interval=30s --timeout=30s --start-period=5s --retries=3 CMD wget -q -O - http://localhost:8080/actuator/health | grep UP || exit 1