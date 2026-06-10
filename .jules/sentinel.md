## 2026-06-10 - [CRITICAL] Unauthenticated CloudShell WebSocket Access (IDOR)
**Vulnerability:** The `/terminal` WebSocket endpoint, used for interactive cloud shell access (AWS/GCP/Azure), was explicitly whitelisted in `SecurityConfig.java` using `.permitAll()`. This allowed any unauthenticated user to connect to a terminal session. Furthermore, the endpoint relied on user-provided `accountId` and `tenantId` query parameters without validating ownership against an authenticated session.

**Learning:** WebSocket endpoints in Spring Security can sometimes be overlooked when configuring global security rules, especially if they are added to `permitAll()` for "ease of development" or troubleshooting. Relying on query parameters for multi-tenancy (`tenantId`) and resource identification (`accountId`) without server-side verification of the authenticated principal creates a massive IDOR (Insecure Direct Object Reference) risk.

**Prevention:**
1. Never whitelist sensitive WebSocket endpoints (like those providing shell access) in `SecurityConfig`.
2. Always resolve the user's identity (Principal) from the `WebSocketSession` using `session.getPrincipal()`.
3. Validate that the requested resource (e.g., `CloudAccount`) belongs to the authenticated user's client/tenant.
4. Use the `tenantId` from the authenticated principal rather than trusting client-provided query parameters.
