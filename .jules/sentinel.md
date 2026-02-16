## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-02-16 - [HIGH] User Enumeration and Predictable OTP in PasswordResetController
**Vulnerability:** The password reset flow leaked user existence via specific error messages ("User not found in Global Directory") and used insecure `java.util.Random` for OTP generation. It also logged plaintext OTPs to the console.
**Learning:** Authentication flows must return generic responses to prevent probing for valid accounts. Multi-tenant systems require silent context switching during the reset process to maintain security boundaries without disclosing user locations.
**Prevention:** Use `SecureRandom` for all security-sensitive tokens. Implement generic "If an account exists..." messaging for all recovery flows. Remove all sensitive logging from production paths.
