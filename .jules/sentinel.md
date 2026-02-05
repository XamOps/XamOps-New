## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-02-05 - [HIGH] OTP Leakage and Weak Random Generation
**Vulnerability:** The password reset process used `java.util.Random` for OTP generation and logged the cleartext OTP to `System.out`. Additionally, exception messages were returned directly to the client.
**Learning:** Standard `Random` is predictable and unsuitable for security tokens. Logging sensitive data like OTPs creates a significant risk of account takeover. Leaking exception messages can assist attackers in fingerprinting the system or understanding the database schema.
**Prevention:** Always use `SecureRandom` for security tokens. Never log secrets or sensitive PII/tokens to stdout or application logs. Use generic error messages in public-facing endpoints.
