FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

ARG EXPOSE_PORT=8080
EXPOSE ${EXPOSE_PORT}

COPY target/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]

HEALTHCHECK --interval=30s --timeout=30s --start-period=5s --retries=3 CMD wget -q -O - http://localhost:8080/actuator/health | grep UP || exit 1
