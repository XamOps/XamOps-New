## 2026-06-14 - Information Leakage and Weak PRNG in Authentication Controllers
**Vulnerability:** Use of `java.util.Random` for security-sensitive OTP generation and returning raw exception messages (`e.getMessage()`) to the client.
**Learning:** Authentication flows often contain boilerplate error handling that leaks internal details (e.g., database errors) and uses non-cryptographic randomness for tokens.
**Prevention:** Always use `java.security.SecureRandom` for OTPs/tokens and return generic, sanitized error messages to the client while logging detailed errors internally.
