## 2026-07-07 - [HIGH] Broad API exposure via wildcard permitAll in SecurityConfig

**Vulnerability:** The Spring Security configuration used a broad wildcard pattern `/api/v1/agent/**` with `permitAll()`. While intended for public agent-facing endpoints (ingest, heartbeat), it inadvertently exposed sensitive management and diagnostic endpoints like `/api/v1/agent/install-info` (which leaked the global API key) and `/api/v1/agent/db/config` (which leaked database credentials for DB agents).

**Learning:** Path-based wildcards in Spring Security are dangerous when a sub-path contains both public and private endpoints. Even if the controller methods have internal checks, the security configuration's broad `permitAll()` can override or bypass expected session-based protections if not carefully scoped.

**Prevention:** Always use explicit, narrow `AntPathRequestMatcher` entries specifying paths and HTTP methods where possible. For APIs that use a mix of session-based and token/key-based authentication, group public endpoints into an explicit whitelist rather than using broad wildcards.
