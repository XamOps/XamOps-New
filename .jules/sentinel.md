## 2025-04-02 - [Broken Access Control in Cloud Shell WebSocket]
**Vulnerability:** The `/terminal` WebSocket endpoint was publicly accessible and trusted client-provided `tenantId` and `accountId` without validation.
**Learning:** WebSocket handlers in Spring do not automatically enforce the same authorization checks as standard REST controllers if not explicitly configured in `SecurityConfig`.
**Prevention:** Always retrieve identity and tenancy information from the `SecurityContext` (or authenticated principal) rather than query parameters, and implement explicit ownership checks for sensitive resources.

## 2025-04-02 - [Over-permissive API Whitelist for Agents]
**Vulnerability:** The `/api/v1/agent/**` path was permitted to all, leaking the global agent API key via `/install-info`.
**Learning:** Broad wildcards in `SecurityConfig.permitAll()` can inadvertently expose sensitive management or metadata endpoints.
**Prevention:** Use granular path matching for public endpoints and ensure management APIs require a valid session or robust authentication.
