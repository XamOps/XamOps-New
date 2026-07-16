## 2026-07-16 - Hardcoded Credentials in Data Initializer
**Vulnerability:** The `DataInitializer.java` component had a hardcoded superadmin password ("SuperAdmin@123") used to seed the initial administrative user. This password was also logged in plaintext during the application startup sequence.
**Learning:** Hardcoding credentials in bootstrap logic is a common pattern for "ease of development" that often survives into production. Furthermore, logging these credentials "for confirmation" creates a persistent sensitive information leak in system logs.
**Prevention:** Always externalize credentials using framework-specific configuration mechanisms (like Spring's `@Value` or `Environment`). Ensure that bootstrap logs mask or omit any sensitive values used during initialization.
