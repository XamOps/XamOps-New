# Jammy (Ubuntu 22.04), not Noble (24.04), purely because of Prowler below.
# Ubuntu 24.04 marks its system Python as externally managed (PEP 668), so the
# `pip3 install prowler` on the next line fails outright with
# "error: externally-managed-environment". Jammy has no such restriction, and
# Jammy + system Python is the combination Prowler has always been installed on
# here — only the JRE version changes with the Java 25 upgrade.
#
# Follow-up if this image should move to Noble: install Prowler into a venv
# (python3 -m venv /opt/prowler && /opt/prowler/bin/pip install prowler) and
# symlink /usr/local/bin/prowler at it, so prowler.executable.path still resolves.
FROM eclipse-temurin:25-jre-jammy
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
