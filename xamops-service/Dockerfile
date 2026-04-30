FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

ARG EXPOSE_PORT=8080
EXPOSE ${EXPOSE_PORT}

COPY target/*.jar app.jar

ENTRYPOINT ["java", \
  "-Xms256m", \
  "-Xmx1536m", \
  "-XX:+UseG1GC", \
  "-XX:MaxGCPauseMillis=200", \
  "-XX:MaxMetaspaceSize=256m", \
  "-XX:+UseStringDeduplication", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

HEALTHCHECK --interval=30s --timeout=30s --start-period=5s --retries=3 CMD wget -q -O - http://localhost:8080/actuator/health | grep UP || exit 1
