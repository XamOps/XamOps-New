# Sentinel Journal 🛡️

## 2026-06-08 - [Hardcoded Credentials and Sensitive Logging in DataInitializer]
**Vulnerability:** Hardcoded default passwords for superadmin and demo users, and plain-text logging of the superadmin password during initialization.
**Learning:** Data seeding components often contain hardcoded "bootstrapping" credentials that can easily leak into production or logs if not properly externalized and sanitized.
**Prevention:** Always use Spring property placeholders with sensible defaults that can be overridden via environment variables, and never log sensitive data like passwords in plain text.
