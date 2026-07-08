## 2026-07-08 - [Secure OTP & Error Sanitization]
**Vulnerability:** Weak random number generation (CWE-330), user enumeration, and information leakage (CWE-209).
**Learning:** Using java.util.Random for OTPs is predictable; returning raw exception messages or specific "user not found" errors facilitates attacks.
**Prevention:** Always use SecureRandom for security-sensitive values, return generic success/error messages, and log raw exceptions internally instead of exposing them.
