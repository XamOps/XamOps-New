## 2024-05-13 - Insecure Password Reset Flow
**Vulnerability:** Found insecure OTP generation using `java.util.Random`, sensitive OTP logging to stdout, and information leakage via detailed exception messages in the password reset flow.
**Learning:** Using predictable random numbers for security tokens and logging them directly compromises the authentication flow. Exposing raw exception messages can leak internal system details to attackers.
**Prevention:** Use `SecureRandom` for all security-sensitive tokens, use proper logging frameworks (SLF4J) instead of stdout, and return generic error messages to clients.
