# Sentinel Journal 🛡️

## 2026-06-12 - Use SecureRandom for OTP generation
**Vulnerability:** Weak random number generation for security-sensitive tokens.
**Learning:** `java.util.Random` is a LCG (Linear Congruential Generator) which is predictable if enough outputs are observed. For authentication tokens like OTPs, cryptographically secure PRNGs like `java.security.SecureRandom` should always be used to prevent prediction attacks.
**Prevention:** Use `java.security.SecureRandom` for any security-sensitive random values, including session IDs, CSRF tokens, and OTP codes. Ensure the range covers all possible values (e.g., `nextInt(1000000)` for a 6-digit code).
