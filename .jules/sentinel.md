## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-02-24 - [HIGH] Insecure Password Reset Flow
**Vulnerability:** The password reset controller used `java.util.Random` for OTP generation, logged OTPs to stdout, and returned descriptive error messages that allowed user existence and email matching enumeration.
**Learning:** Security-sensitive tokens must use cryptographically secure random number generators. Logging secrets or providing specific feedback on whether a user exists in a reset flow allows attackers to map the user base.
**Prevention:** Use `SecureRandom` for all tokens. Return generic "If your account exists..." messages in authentication flows. Use proper logging frameworks and avoid logging sensitive data.
