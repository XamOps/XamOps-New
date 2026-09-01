FROM eclipse-temurin:25-jre-noble
WORKDIR /app

ARG EXPOSE_PORT=8080
EXPOSE ${EXPOSE_PORT}

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && \
    apt-get install -y --no-install-recommends python3 python3-pip && \
    pip3 install --no-cache-dir prowler && \
    rm -rf /var/lib/apt/lists/*

COPY target/*.jar app.jar

ENTRYPOINT ["java", \
  "-Xms512m", \
  "-Xmx1536m", \
  "-XX:+UseG1GC", \
  "-XX:MaxGCPauseMillis=200", \
  "-XX:MaxMetaspaceSize=512m", \
  "-XX:+UseStringDeduplication", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

HEALTHCHECK --interval=30s --timeout=30s --start-period=5s --retries=3 CMD wget -q -O - http://localhost:8080/actuator/health | grep UP || exit 1
