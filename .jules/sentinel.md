## 2026-04-16 - Broad API Whitelisting Leak
**Vulnerability:** A broad AntPathRequestMatcher for `/api/v1/agent/**` in `SecurityConfig.java` unintentionally exposed the `/install-info` endpoint, which returned the global agent API key to unauthenticated users.
**Learning:** Wildcard whitelists can easily capture sensitive management or information endpoints that should remain restricted to authenticated users or internal calls.
**Prevention:** Always use specific paths for whitelisting (e.g., `/api/v1/agent/ingest/**`) and audit all endpoints within a controller to ensure they match the intended security posture of the whitelist.
