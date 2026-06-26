
## 2026-06-25 - Hardening OTP Generation and Error Sanitization
**Vulnerability:** Insecure randomness (CWE-330) in OTP generation and information leakage (CWE-209) in error responses.
**Learning:** Controllers used `java.util.Random` for 6-digit OTPs, which is cryptographically weak. Additionally, `GlobalExceptionHandler` and `PasswordResetController` leaked raw exception messages to the client.
**Prevention:** Use `java.security.SecureRandom` for all security-sensitive tokens. Ensure exception handlers log full details internally but return generic, sanitized messages to the client.
