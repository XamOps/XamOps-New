## 2026-07-05 - Attack Surface Exposure via Broad Wildcards
**Vulnerability:** Broad `permitAll()` wildcard patterns in Spring Security (e.g., `/api/v1/agent/**`) inadvertently exposed sensitive management endpoints like `/install-info` (leaking global API keys) and `/db/config` (leaking database credentials) to unauthenticated users.
**Learning:** Overly permissive security configurations often hide sensitive sub-paths that developers assume are protected. Common patterns like `/**` should be avoided for public access.
**Prevention:** Use explicit, narrow `AntPathRequestMatcher` entries for public endpoints. Always verify the full list of endpoints under a path prefix before applying a wildcard `permitAll()`.
