## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-02-08 - [HIGH] Over-permissive Security Configuration
**Vulnerability:** Numerous sensitive internal pages (e.g., `/user-manager.html`, `/gcp_*.html`) and powerful APIs (e.g., `/terminal`, `/ws/**`, `/api/cicd/**`) were listed in the `permitAll()` whitelist of the Spring Security configuration.
**Learning:** Legacy debug endpoints or incomplete security refactors can leave critical parts of the application exposed to unauthenticated users. Even if the backend logic has some checks, the first line of defense (SecurityConfig) must be robust.
**Prevention:** Regularly audit the `permitAll()` list and ensure it only contains truly public resources like login pages and static assets. Enforce authentication by default for all application-specific logic.
