## 2026-07-15 - [Credential Externalization]
**Vulnerability:** Hardcoded superadmin credentials and plaintext logging in DataInitializer.java.
**Learning:** Hardcoded credentials in bootstrap logic are often overlooked and can be leaked via version control or application logs.
**Prevention:** Always externalize credentials to configuration properties with environment variable overrides and ensure sensitive data is never logged in plaintext.
