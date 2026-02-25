## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-02-25 - [MEDIUM] Overly Permissive Access Control and Sensitive Data Logging
**Vulnerability:** Numerous sensitive management pages, connectivity endpoints (WebSocket), and cloud-related APIs were listed in the `permitAll()` block of `SecurityConfig.java`, making them accessible without authentication. Additionally, `CommonsRequestLoggingFilter` was configured to include request payloads and headers, which could leak credentials and session tokens into application logs.
**Learning:** Broad `permitAll()` configurations can inadvertently expose sensitive application internals. Defaulting to deny and explicitly allowing only essential public resources is safer. Logging payloads and headers is risky in production environments.
**Prevention:** Regularly audit `SecurityConfig` to ensure only truly public endpoints are in the `permitAll()` list. Configure logging filters to exclude sensitive data by default (`setIncludePayload(false)`, `setIncludeHeaders(false)`).
