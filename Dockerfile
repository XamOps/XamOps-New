# # Stage 1: Build the application using Maven
# FROM maven:3.8.5-openjdk-17 AS build
# WORKDIR /app

# # Copy the entire project context
# COPY . .

# # Build the application
# ARG MODULE_NAME=xamops-service
# RUN mvn clean package -pl ${MODULE_NAME} -am -DskipTests

# # Stage 2: Create the final, lightweight runtime image
# FROM eclipse-temurin:17-jre-jammy
# WORKDIR /app

# # --- CRITICAL FIX: Install Docker CLI HERE (Before Copy/Entrypoint) ---
# RUN apt-get update && \
#     apt-get install -y docker.io && \
#     rm -rf /var/lib/apt/lists/*

# # The port the application will run on
# ARG EXPOSE_PORT=8080
# EXPOSE ${EXPOSE_PORT}

# # Copy only the built JAR file (Hardcoded path to avoid build-arg errors)
# COPY --from=build /app/xamops-service/target/*.jar app.jar

# # The command to run the application
# ENTRYPOINT ["java", "-jar", "app.jar"]

# # Health check
# HEALTHCHECK --interval=30s --timeout=30s --start-period=5s --retries=3 CMD wget -q -O - http://localhost:8080/actuator/health | grep UP || exit 1


# ===================================================================
# Stage 1: Build the application using Maven
# ===================================================================
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app

# Copy the entire project context
COPY . .

# Build the application (Skipping tests for speed)
ARG MODULE_NAME=xamops-service
RUN mvn clean package -pl ${MODULE_NAME} -am -DskipTests

# --- KEY FIX: Move the built JAR to a standard location ---
RUN cp ${MODULE_NAME}/target/*.jar /app/app.jar

# ===================================================================
# Stage 2: Create the final runtime image with ALL Cloud CLIs
# ===================================================================
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# 1. Install Core Utilities & Dependencies (including Python & Docker CLI)
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y \
    curl \
    wget \
    unzip \
    jq \
    vim \
    nano \
    groff \
    less \
    lsb-release \
    gnupg \
    software-properties-common \
    ca-certificates \
    apt-transport-https \
    docker.io \
    python3 \
    python3-pip \
    && rm -rf /var/lib/apt/lists/*

# 2. Install AWS CLI v2
RUN curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip" && \
    unzip awscliv2.zip && \
    ./aws/install && \
    rm -rf aws awscliv2.zip

# 3. Install Azure CLI
RUN curl -sL https://aka.ms/InstallAzureCLIDeb | bash

# 4. Install Google Cloud SDK
RUN echo "deb [signed-by=/usr/share/keyrings/cloud.google.gpg] https://packages.cloud.google.com/apt cloud-sdk main" | \
    tee -a /etc/apt/sources.list.d/google-cloud-sdk.list && \
    curl https://packages.cloud.google.com/apt/doc/apt-key.gpg | \
    gpg --dearmor -o /usr/share/keyrings/cloud.google.gpg && \
    apt-get update && \
    apt-get install -y google-cloud-cli && \
    rm -rf /var/lib/apt/lists/*

# 5. Configuration (Port & Entrypoint)
ARG EXPOSE_PORT=8080
EXPOSE ${EXPOSE_PORT}

# --- KEY FIX: Copy from the standardized location ---
COPY --from=build /app/app.jar app.jar

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]

# Health check
HEALTHCHECK --interval=30s --timeout=30s --start-period=30s --retries=3 \
  CMD wget -q -O - http://localhost:8080/actuator/health | grep UP || exit 1