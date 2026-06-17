# Sentinel Security Journal

## 2026-06-16 - Prevent information leakage via error messages (CWE-209)
**Vulnerability:** API endpoints and global exception handlers were returning raw `ex.getMessage()` to clients.
**Learning:** This pattern was widespread in `xamops-service` controllers (e.g., `LlmProxyController`, `GlobalExceptionHandler`), exposing internal logic and system details when unhandled RuntimeExceptions occurred.
**Prevention:** Always use generic, sanitized user-facing error messages for unhandled exceptions. Log the detailed error internally for debugging, but never expose it to the client.
