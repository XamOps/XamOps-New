## 2026-05-27 - Multi-tenant Isolation and Management Endpoint Hardening
**Vulnerability:** Several management endpoints in `AgentController` (status, registration, configuration) were publicly accessible without session authentication and lacked ownership validation, leading to potential global API key leakage and IDOR.
**Learning:** Broad `permitAll()` rules for API paths (e.g., `/api/v1/agent/**`) can inadvertently expose sensitive management functionality that was intended for UI consumption rather than agent communication.
**Prevention:** Explicitly list agent-facing endpoints that use API key authentication in `SecurityConfig.java` and ensure all other management endpoints require a valid user session and perform ownership validation against the user's `clientId`.
