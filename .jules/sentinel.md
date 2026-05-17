## 2024-05-17 - Broad Wildcard API Whitelisting
**Vulnerability:** The `/api/v1/agent/**` path was broadly whitelisted with `permitAll()` in `SecurityConfig.java`, exposing management endpoints like `/status` and `/db/config` to unauthenticated public access.
**Learning:** Mixing public/ingest APIs with management APIs under the same path prefix while using wildcard whitelisting creates a significant security risk by bypassing authentication for sensitive administrative operations.
**Prevention:** Use granular `AntPathRequestMatcher` rules that specify both the exact endpoint path and the required HTTP method (e.g., `POST /api/v1/agent/ingest`) instead of broad wildcards. Ensure management endpoints require full session authentication and multi-tenant ownership validation.
