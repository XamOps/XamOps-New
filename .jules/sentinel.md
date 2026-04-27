## 2026-04-23 - Hardened Agent API Whitelist
**Vulnerability:** The broad security whitelist for `/api/v1/agent/**` allowed unauthorized access to sensitive management endpoints. Specifically, the `/api/v1/agent/install-info` endpoint leaked the global agent API key, and `/api/v1/agent/db/register` allowed unauthorized agent registrations.
**Learning:** Overly permissive wildcards in security configurations can inadvertently expose management or diagnostic endpoints that were intended to be internal or session-protected.
**Prevention:** Always use the Principle of Least Privilege when defining security whitelists. Explicitly list only the sub-paths that require public or API-key-only access, rather than whitelisting an entire path hierarchy.
