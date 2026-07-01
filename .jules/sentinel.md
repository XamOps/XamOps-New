## 2026-07-01 - Overly Permissive Agent API Whitelist
**Vulnerability:** Broad wildcard permitAll on `/api/v1/agent/**` in `SecurityConfig.java`.
**Learning:** Wildcard patterns in security configurations can inadvertently expose sensitive management endpoints (like `/install-info` and `/db/config`) that were intended only for authenticated users or specialized agent auth.
**Prevention:** Always use explicit, narrow AntPathRequestMatchers for public APIs, specifying both the exact path and, where possible, the allowed HTTP method (e.g., POST for data submission).
