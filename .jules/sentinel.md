# Sentinel Security Journal

## 2026-07-18 - Secure Random for OTP Generation in PasswordResetController
**Vulnerability:** Weak/predictable random number generation using `java.util.Random` for OTP (One-Time Password) generation in PasswordResetController (CWE-330).
**Learning:** `java.util.Random` is a pseudorandom number generator (PRNG) that is not cryptographically secure, allowing attackers to predict future values if they can determine the seed.
**Prevention:** Always use `java.security.SecureRandom` instead of `java.util.Random` for security-sensitive operations such as generating verification codes, session IDs, and keys.
