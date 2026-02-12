## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-02-12 - [CRITICAL] Hardcoded Secrets in Configuration Files
**Vulnerability:** Sensitive credentials (database passwords, mail server passwords, AI service API keys, and encryption keys) were stored as plaintext in Spring Boot property files (`application*.properties`).
**Learning:** Storing secrets in version control is a major security risk. Even if they are defaults for local dev, they should be moved to environment variables to prevent accidental exposure or misuse in production environments.
**Prevention:** Use Spring property placeholders (e.g., `${SECRET_NAME}`) and inject actual values via environment variables or a secure secret manager (like Google Cloud Secret Manager). Provide non-sensitive defaults ONLY for local development.
