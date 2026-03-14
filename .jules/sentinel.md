## 2026-03-14 - Hardcoded Credentials and Plaintext Logging in Data Initializers
**Vulnerability:** SuperAdmin credentials were hardcoded in `DataInitializer.java` and logged to the console in plaintext upon creation.
**Learning:** Developer convenience led to hardcoding default passwords and logging them for easy verification during development, which persisted into the codebase.
**Prevention:** Use Spring `@Value` with environment variable placeholders for all initial credentials and strictly avoid logging sensitive data like passwords, even during one-time initialization events.
