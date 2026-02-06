## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-02-06 - [CRITICAL] Widespread Hardcoded Secrets in Configuration and Code
**Vulnerability:** Numerous sensitive credentials (DB passwords, AI API keys, email passwords, encryption keys) were hardcoded in property files (`application.properties`), Python config, Java code, and CI/CD pipelines (`Jenkinsfile`, `deploy.yml`).
**Learning:** Developers often leave default "placeholders" (e.g., `${KEY:AIza...}`) or "dummy" values (e.g., `AppPassword123`) in configuration files for ease of local development, but these often survive into production or expose real keys in the repository history.
**Prevention:** Use environment variable placeholders without default values for all sensitive data. Inject secrets into code using established patterns (e.g., `@Value` in Spring) rather than hardcoding constants. Use CI/CD secret management (Jenkins Credentials, GitHub Secrets) to inject keys at runtime.
