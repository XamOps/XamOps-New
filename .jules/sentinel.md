## 2026-06-23 - Hardened Agent API Path Security
**Vulnerability:** Broad permit-all rule on `/api/v1/agent/**` exposed sensitive management endpoints and leaked the global agent API key via `/api/v1/agent/install-info` to unauthenticated users.
**Learning:** Mixing agent-facing APIs (which use custom API key auth) and UI-facing management APIs (which require session auth) under the same path prefix is dangerous if a wildcard permit-all rule is applied.
**Prevention:** Use explicit AntPathRequestMatchers for only the specific endpoints that require public/custom authentication, ensuring all other endpoints under the prefix default to requiring a valid user session.
