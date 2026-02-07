## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-02-07 - [HIGH] Publicly Accessible Cloud Shell and Sensitive Dashboards
**Vulnerability:** Multiple sensitive endpoints, including the /terminal WebSocket (Cloud Shell), /ws/** (STOMP), and various dashboard HTML pages and APIs, were included in the `permitAll()` list in `SecurityConfig.java`.
**Learning:** Developers often add endpoints to `permitAll()` for convenience during development or troubleshooting, which can accidentally persist into production, leaving critical features exposed to unauthenticated users.
**Prevention:** Follow the principle of "Secure by Default". Only explicitly whitelist truly public resources (login, static assets, password reset). Periodically audit the `permitAll()` list to ensure no sensitive features are exposed.
