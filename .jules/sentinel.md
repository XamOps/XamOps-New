## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-02-15 - [HIGH] User Enumeration and Weak OTP in PasswordResetController
**Vulnerability:** The password reset flow returned specific error messages (e.g., "User not found", "Email does not match") allowing an attacker to enumerate valid users. Additionally, it used `java.util.Random` for OTP generation and logged sensitive OTPs to system output.
**Learning:** Returning detailed error messages in authentication-related flows leaks information about account existence. Using non-cryptographic random number generators for security tokens like OTPs makes them predictable.
**Prevention:** Always return generic success/failure messages in authentication flows. Use `SecureRandom` for security tokens and ensure sensitive data is never logged or printed to stdout.
