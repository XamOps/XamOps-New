
## 2026-07-03 - Hardening OTP Generation and Error Handling
**Vulnerability:** Insecure `java.util.Random` used for 6-digit OTP generation and raw exception messages leaked to the client in `PasswordResetController`.
**Learning:** Standard `Random` is predictable and not suitable for security tokens. Uncaught exceptions or direct error returns leaked internal logic.
**Prevention:** Always use `java.security.SecureRandom` for sensitive tokens and return generic, sanitized error messages to the client while logging details internally.
