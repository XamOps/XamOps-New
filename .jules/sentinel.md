## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-02-26 - [MEDIUM] User Enumeration and Information Leakage in Password Reset
**Vulnerability:** The password reset flow returned distinct error messages for missing users vs. valid users, and leaked raw exception messages globally.
**Learning:** Returning specific error messages like "User not found" allows attackers to enumerate valid usernames. Including `ex.getMessage()` in global error responses can expose internal system details.
**Prevention:** Always return generic success/failure messages for authentication-related flows. Sanitize global error responses to provide only a generic message to the client while logging details internally.
