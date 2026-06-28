
## 2026-06-25 - Hardcoded SuperAdmin Password in DataInitializer
**Vulnerability:** A hardcoded plaintext password ("SuperAdmin@123") was used in `DataInitializer.java` to bootstrap the superadmin user, and the credentials were logged in cleartext to the console.
**Learning:** Initializing default administrative users via `CommandLineRunner` often leads to "temporary" hardcoded credentials being left in the source code.
**Prevention:** Always externalize bootstrapping passwords using Spring property placeholders (`@Value`) with environment variable overrides, and ensure that logs only confirm the creation of the user without exposing the password.
