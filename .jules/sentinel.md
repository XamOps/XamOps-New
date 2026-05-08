## 2026-05-08 - IDOR and Secret Leakage in Agent Management
**Vulnerability:** The AgentController management endpoints (e.g., /db/config, /db/register) lacked multi-tenant isolation checks (IDOR) and exposed plaintext database credentials. Furthermore, these endpoints were broadly permitted in SecurityConfig.
**Learning:** Broad 'permitAll()' paths in SecurityConfig (like /api/v1/agent/**) can accidentally expose sensitive management endpoints if they share a common path prefix with intended public/API-key-protected endpoints.
**Prevention:** Always implement explicit ownership validation (matching resource owner clientId to authenticated user clientId) in controllers, even if the path seems protected. Redact sensitive fields (like passwords) in all GET responses.
