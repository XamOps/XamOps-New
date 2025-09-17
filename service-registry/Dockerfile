# --- Stage 1: The Builder ---
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY . .
RUN mvn clean package -pl service-registry -am -DskipTests

# --- Stage 2: The Final Image ---
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
ARG JAR_FILE_PATH=service-registry/target/service-registry-1.0.0.jar
COPY --from=builder /app/${JAR_FILE_PATH} app.jar
EXPOSE 8761
# CORRECTED ENTRYPOINT
ENTRYPOINT ["java", "-cp", "app.jar", "org.springframework.boot.loader.JarLauncher", "com.xammer.service_registry.ServiceRegistryApplication"]
