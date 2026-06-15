## 2026-06-15 - Narrowed Agent API Whitelist
**Vulnerability:** Broad permitAll on `/api/v1/agent/**` leaked the global agent API key via `/api/v1/agent/install-info`.
**Learning:** Wildcard exemptions in security configurations can inadvertently expose management or sensitive endpoints.
**Prevention:** Always use explicit allow-lists for public endpoints and move management or sensitive metadata endpoints under authenticated paths.
