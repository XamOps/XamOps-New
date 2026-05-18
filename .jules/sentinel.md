## 2025-05-18 - Harden OTP Generation and Error Handling
**Vulnerability:** Use of `java.util.Random` for OTP generation, sensitive data logging via `System.out.println`, and information leakage through raw exception messages in API responses.
**Learning:** The authentication flow used predictable pseudo-random numbers for 6-digit codes and exposed these codes in standard output. Additionally, internal implementation details were leaked to clients via `e.getMessage()`.
**Prevention:** Always use `java.security.SecureRandom` for sensitive tokens/codes, use SLF4J for logging (avoiding stdout), and return generic error messages to clients while logging detailed errors on the server.
