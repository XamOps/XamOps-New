## 2026-05-23 - Information Leakage via Error Messages
**Vulnerability:** API endpoints in `GlobalExceptionHandler`, `LlmProxyController`, and `ObservabilityController` were returning raw exception messages (`e.getMessage()`) to clients.
**Learning:** Returning unmasked exception messages can expose internal system details, library versions, and database structure (CWE-209), aiding reconnaissance for attackers.
**Prevention:** Always catch and wrap exceptions at the controller or global level to return generic, user-friendly error messages while ensuring detailed exceptions are still logged server-side for debugging.
