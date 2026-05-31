## 2025-05-14 - Information Leakage via Exception Messages
**Vulnerability:** Information Leakage (CWE-209) through raw exception messages in API responses.
**Learning:** Multiple controllers and the `GlobalExceptionHandler` were found to be returning `e.getMessage()` directly to the client. This can leak sensitive internal details about the application's structure, database schema, or third-party integrations when unexpected errors occur.
**Prevention:** Standardize on returning generic, user-friendly error messages for all unhandled or runtime exceptions. Ensure detailed exception information is only recorded in server-side logs for debugging purposes.
