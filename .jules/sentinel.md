## 2026-05-02 - Agent Management IDOR and Sensitive Data Exposure
**Vulnerability:** The `AgentController` exposed sensitive database agent management endpoints (registration, configuration, and status) without any ownership validation. Combined with a broad `permitAll("/api/v1/agent/**")` in `SecurityConfig`, this allowed any authenticated (or even unauthenticated) user to access or modify database credentials and agent status for any account by simply providing the `cloudAccountId` or `agentName`.

**Learning:** Broad `permitAll()` path patterns in `SecurityConfig` are dangerous as they shift the entire security responsibility to the controller level. If developers assume the framework handles multi-tenancy based on session/impersonation context but then expose paths explicitly without auth, it creates a massive IDOR surface. Additionally, returning sensitive fields like `dbPassword` in "load config" endpoints is a common but high-risk pattern.

**Prevention:**
1. Always implement internal `validateOwnership` helpers in controllers when paths are exposed via `permitAll()` or when multi-tenant isolation relies on user-provided IDs.
2. Redact sensitive fields (passwords, tokens) in API responses even if the endpoint is "internal" or intended only for administrative UI.
3. Use a "placeholder check" pattern in update endpoints (e.g., ignore `********`) to allow safe UI round-trips without accidental credential exposure or overwrite.
