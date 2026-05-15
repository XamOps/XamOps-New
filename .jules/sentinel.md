## 2026-05-15 - IDOR and Information Disclosure in AgentController
**Vulnerability:** Several management endpoints in `AgentController` (e.g., `/status`, `/events`, `/db/config`) lacked ownership validation, allowing authenticated users to access data of other tenants. Additionally, the `/install-info` endpoint was publicly accessible and leaked the global agent API key.
**Learning:** Endpoints under a `permitAll()` path in Spring Security (intended for API key auth) must manually enforce session-based ownership checks when used for dashboard/UI functionality.
**Prevention:** Always validate resource ownership against the current user's `clientId` in controllers, and explicitly enforce authentication on endpoints returning sensitive configuration like API keys.
