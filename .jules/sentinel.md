## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-02-14 - [CRITICAL] Widespread Hardcoded Secrets in Property Files
**Vulnerability:** Widespread use of hardcoded secrets (DB passwords, API keys, email credentials, encryption keys) across `billops` and `xamops-service` in all environment profiles (`application.properties`, `-local`, `-uat`, `-prod`).
**Learning:** Even after refactoring code to use `@Value` annotations, developers often leave the actual secret values in the configuration files, especially in non-production profiles which then get copied to production.
**Prevention:** Never commit actual secret values to property files. Always use `${PLACEHOLDER}` syntax and provide values via environment variables or a secure secret manager. Remove default values from placeholders in all environments.
