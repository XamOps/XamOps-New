## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-02-22 - [CRITICAL] Hardcoded Secrets in Properties and Source Code
**Vulnerability:** Plaintext database passwords, mail credentials, and API keys (Gemini, Groq, AnythingLLM, AutoSpotting) were hardcoded in `application.properties` files and even within Java source code (`AutoSpottingController.java`).
**Learning:** Developers sometimes hardcode secrets for "testing" or "convenience," intending to fix them later, but these often remain in the codebase. Committing secrets to version control is a major risk as they are preserved in history.
**Prevention:** Use Spring environment variable placeholders (`${VAR_NAME}`) for all sensitive configurations. Never provide sensitive defaults in properties files. Refactor source code to inject configurations via `@Value` or `ConfigurationProperties` rather than using hardcoded string literals.
