## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-02-18 - [HIGH] Broken Access Control & Information Leakage
**Vulnerability:** Numerous sensitive management endpoints and HTML pages (e.g., /terminal, /user-manager.html, /api/cicd/**) were configured as `permitAll()` in SecurityConfig.java. Additionally, `CommonsRequestLoggingFilter` was configured to log full request payloads and headers, potentially exposing passwords and tokens in plaintext logs.
**Learning:** Defaulting to `permitAll()` for experimental or new features is a common "shortcut" that often remains in production code, creating massive security holes. Similarly, overly verbose logging intended for debugging can become a significant security risk if not curtailed before deployment.
**Prevention:** Follow the principle of least privilege: start with all endpoints authenticated and only explicitly permit those that MUST be public. Always disable payload and header logging in production-ready configurations.
