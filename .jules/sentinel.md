## 2026-05-20 - Insecure OTP Generation and Information Leakage
**Vulnerability:** Use of `java.util.Random` for security-sensitive OTP generation, logging of OTPs to `System.out`, and exposure of internal error messages in authentication controllers.
**Learning:** Developers used standard PRNGs and console logging for debugging, which persisted into the codebase, creating predictable tokens and side-channel leaks.
**Prevention:** Always use `java.security.SecureRandom` for tokens, enforce SLF4J for all logging, and return generic error messages to clients to prevent user enumeration and information disclosure.
