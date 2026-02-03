## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-02-03 - [CRITICAL] Hardcoded Secrets in Property Files
**Vulnerability:** Multiple sensitive credentials (database passwords, Google App passwords, API keys for Gemini, Groq, AnythingLLM, and AutoSpotting, and the application encryption secret key) were hardcoded across `application.properties` and profile-specific files (`-local`, `-uat`, `-prod`).
**Learning:** Hardcoding secrets in version control exposes them to anyone with repository access and makes it difficult to rotate them. Even "default" values in placeholders (e.g., `${KEY:DEFAULT}`) are unsafe if they contain real secrets.
**Prevention:** Always use environment variable placeholders (e.g., `${VARIABLE_NAME}`) for sensitive information in configuration files. Ensure that no real secrets are committed even as local development defaults.
