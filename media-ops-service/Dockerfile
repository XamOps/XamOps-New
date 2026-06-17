FROM eclipse-temurin:17-jre-jammy

# Install FFmpeg — baked into the image so UAT/prod never need host-level installs
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Temp storage for in-flight transcoding jobs (S3 upload happens after, then this is cleared)
RUN mkdir -p /data/xamops-media && chmod 777 /data/xamops-media

ARG EXPOSE_PORT=8084
EXPOSE ${EXPOSE_PORT}

COPY target/*.jar app.jar

ENTRYPOINT ["java", \
  "-Xms512m", \
  "-Xmx3g", \
  "-XX:+UseG1GC", \
  "-XX:MaxGCPauseMillis=200", \
  "-XX:MaxMetaspaceSize=512m", \
  "-XX:+UseStringDeduplication", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

HEALTHCHECK --interval=30s --timeout=10s --start-period=15s --retries=3 \
  CMD wget -q -O - http://localhost:8084/actuator/health | grep UP || exit 1
