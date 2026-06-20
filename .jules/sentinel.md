## 2026-06-18 - Hardening Agent Management Endpoints
**Vulnerability:** Public exposure of management endpoints under `/api/v1/agent/**`, including `/install-info` which leaked the global agent API key and `/db/config` which leaked database credentials.
**Learning:** Broad wildcard whitelists (like `/api/v1/agent/**` for agent-facing APIs) can accidentally expose sensitive management or configuration endpoints if they share the same path prefix.
**Prevention:** Use explicit matchers for public or API-key-authenticated endpoints instead of broad wildcards to ensure that new management endpoints are secured by default.
