
## 2025-05-15 - Cryptographically Secure OTP Generation
**Vulnerability:** Use of java.util.Random for generating security-sensitive 6-digit OTPs in LoginOtpController and PasswordResetController.
**Learning:** java.util.Random is a PRNG that is not cryptographically secure, making OTPs potentially predictable if the seed is discovered or enough values are observed. Additionally, nextInt(999999) was used, which only produces values up to 999,998, excluding 999,999.
**Prevention:** Use java.security.SecureRandom for all security-sensitive random values. Ensure the upper bound in nextInt() correctly covers the intended range (e.g., 1000000 for 6 digits).
