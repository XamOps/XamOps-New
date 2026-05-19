## 2025-05-19 - Hardened OTP Generation and Error Masking
**Vulnerability:** Insecure OTP generation and user enumeration via specific error messages.
**Learning:** Using `java.util.Random` for security tokens is insecure because its output is predictable. Additionally, returning specific error messages for failed password resets (e.g., "Email does not match") allows attackers to enumerate valid usernames/emails.
**Prevention:** Always use `java.security.SecureRandom` for cryptographic tokens like OTPs. Mask authentication-related error messages to return generic responses (e.g., "If the account exists, an OTP has been sent") to prevent user enumeration.
