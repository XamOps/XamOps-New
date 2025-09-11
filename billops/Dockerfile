# --- Stage 1: The Builder ---
# Use a specific Maven and Java version to build our application
FROM maven:3.9-eclipse-temurin-17 AS builder

# Set the working directory in the container
WORKDIR /app

# Copy the entire project context into the builder stage
COPY . .

# Build only the 'billops' module and its dependencies
RUN mvn clean package -pl billops -am -DskipTests


# --- Stage 2: The Final Image ---
# Use a lightweight JRE-only image for a smaller, more secure final container
FROM eclipse-temurin:17-jre-alpine

# Set the working directory
WORKDIR /app

# Argument to define the path to the JAR file from the builder stage
ARG JAR_FILE_PATH=billops/target/billops-0.0.1-SNAPSHOT.jar

# Copy only the built JAR file from the 'builder' stage
COPY --from=builder /app/${JAR_FILE_PATH} app.jar

# Expose the port the application runs on
EXPOSE 8082

# HEALTHCHECK instruction is removed as the Spring Boot Actuator dependency is not present.

# The command to run the application. Add JVM arguments here if needed (e.g., "-Xmx512m").
ENTRYPOINT ["java", "-jar", "app.jar"]

