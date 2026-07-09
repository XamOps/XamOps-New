## 2026-07-09 - CWE-209: Sensitive Information Leakage in Error Messages
**Vulnerability:** Unhandled exceptions in `GlobalExceptionHandler` were returning raw exception messages to the client via `ex.getMessage()`.
**Learning:** Returning detailed exception messages in production can leak internal implementation details, such as database connection strings, file paths, or class names, which can be used by attackers to map the application's internals.
**Prevention:** Always use generic error messages for unhandled exceptions in global handlers. Ensure detailed logs are still captured on the server-side for debugging purposes, but never expose them to the end-user.
