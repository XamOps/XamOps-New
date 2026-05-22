## 2026-05-22 - Predictable OTP and Information Leakage in Password Reset
**Vulnerability:** Predictable 6-digit OTPs generated via `java.util.Random`, sensitive OTP leakage in console logs, and user enumeration/internal detail leakage via explicit error messages and `e.getMessage()` in API responses.
**Learning:** Default PRNGs are insufficient for security tokens. Explicit error messages like "User not found" or "Email mismatch" allow attackers to map valid accounts. Raw exception messages in `ResponseEntity` can leak database schema or internal logic.
**Prevention:** Always use `java.security.SecureRandom` for tokens. Return generic success/failure messages for auth flows (e.g., "If the account exists, an OTP has been sent"). Log detailed errors server-side only.
