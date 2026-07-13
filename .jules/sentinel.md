# Sentinel 🛡️ - Security Journal

## 2026-07-13 - [Hardening Error Responses and Agent API]
**Vulnerability:** Information Exposure (CWE-209) and Missing Authorization (CWE-285) in AgentController.
**Learning:** Broad `permitAll()` rules in `SecurityConfig.java` can inadvertently expose sensitive UI-facing endpoints in controllers that are otherwise used by external agents. Raw exception messages in `GlobalExceptionHandler` leaked internal system details.
**Prevention:** Use `@PreAuthorize("authenticated")` on UI-facing endpoints within controllers that have broad path-based permit rules. Sanitize all unhandled exception responses to return generic messages.
