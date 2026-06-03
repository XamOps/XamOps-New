## 2026-06-03 - [Information Exposure (CWE-209) in Observability & Global Error Handlers]
**Vulnerability:** API endpoints in `ObservabilityController` and the global `globalExceptionHandler` were returning raw `exception.getMessage()` to clients.
**Learning:** Returning internal exception details can leak sensitive information about the application's environment, dependencies, or data structures, facilitating reconnaissance for attackers.
**Prevention:** Implement a standardized generic error message for all user-facing API responses. Ensure internal logs still capture full exception details for debugging while keeping external responses opaque.
