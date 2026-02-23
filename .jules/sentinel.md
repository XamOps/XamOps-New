## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-02-23 - [CRITICAL] Secret Exposure and Broken Access Control
**Vulnerability:** Numerous sensitive credentials (DB, SMTP, API keys) were hardcoded in plaintext across multiple Spring Boot properties files. Additionally, sensitive administrative pages (e.g., `/user-manager.html`) and internal APIs were exposed via `permitAll()` in the security configuration.
**Learning:** Security regressions or incomplete implementations can leave critical assets exposed. Relying on "preserved" code can inadvertently re-introduce vulnerabilities.
**Prevention:** Strictly use environment variable placeholders for all secrets. Maintain a "deny-by-default" approach for security configurations, only explicitly permitting public static resources.
