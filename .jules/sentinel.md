# Sentinel Security Journal

## 2026-07-04 - Sanitized Error Messages (CWE-209)
**Vulnerability:** Information Exposure through Error Messages.
**Learning:** GlobalExceptionHandler was leaking raw exception messages to the client, potentially exposing internal system details.
**Prevention:** Always return generic error messages to the end-user while logging detailed exception info for internal debugging.

## 2026-07-04 - Hardened OTP Generation (CWE-330)
**Vulnerability:** Use of Insufficiently Random Values.
**Learning:** java.util.Random was used for 6-digit OTP generation, which is not cryptographically secure.
**Prevention:** Use java.security.SecureRandom for generating security-sensitive tokens like OTPs.
