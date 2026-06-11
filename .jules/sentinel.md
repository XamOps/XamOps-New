## 2026-06-12 - Secure OTP Generation and Error Sanitization
**Vulnerability:** Weak random number generation (java.util.Random) for security-sensitive OTPs and information leakage via detailed error messages (printStackTrace and e.getMessage()).
**Learning:** OTP generation for password reset and login was using a predictable pseudo-random number generator (`java.util.Random`). Additionally, `PasswordResetController` was exposing internal exception details directly to the client, which can aid in further exploitation.
**Prevention:** Always use `java.security.SecureRandom` for security tokens. Implement consistent error handling that logs detailed information internally (e.g., using Slf4j) while returning sanitized, generic messages to the client.
