# Stage 1: Build the application using Maven
FROM maven:3.8.5-openjdk-17 AS build

# Set the working directory
WORKDIR /app

# Copy the entire project context (parent POM and all modules)
COPY . .

# Build the specific module.
# The `pl` flag tells Maven to build a specific project/module.
# We use a variable `MODULE_NAME` which we'll set during the build command.
RUN mvn clean package -DskipTests

# Stage 2: Create the final, lightweight runtime image
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# The port the application will run on inside the container.
# We'll set this as an argument for flexibility.
ARG EXPOSE_PORT=8080
EXPOSE ${EXPOSE_PORT}

# Copy only the built JAR file from the specific module's target directory
ARG MODULE_NAME
COPY --from=build /app/xamops-service/target/*.jar app.jar

# The command to run the application when the container starts
ENTRYPOINT ["java", "-jar", "app.jar"]
