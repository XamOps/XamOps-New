## 2025-05-14 - [Secure OTP Generation and Logging]
**Vulnerability:** Weak random number generation (java.util.Random) for OTPs and sensitive OTP logging to stdout.
**Learning:** Standard Random is predictable and unsuitable for security-sensitive tokens like OTPs. Logging OTPs to stdout can leak them to log aggregation systems or anyone with console access.
**Prevention:** Use java.security.SecureRandom for all security tokens and never log raw OTPs or secrets.
