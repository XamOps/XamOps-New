## 2026-03-23 - Hardcoded Credentials & Plaintext Logging in Data Initialization
**Vulnerability:** Hardcoded "SuperAdmin@123" password and plaintext password logging in `DataInitializer.java`.
**Learning:** Initial application seeding often contains "temporary" or "demo" credentials that end up in production codebases, and helpful "creation success" logs can inadvertently leak these credentials.
**Prevention:** Always use property-based or environment variable-based credential injection with safe defaults for local development, and ensure that success logs for sensitive operations like user creation do not echo the input credentials.
