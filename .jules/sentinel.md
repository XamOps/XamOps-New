## 2026-03-21 - Narrowed Agent API PermitAll Scope
**Vulnerability:** Broad wildcard permitAll on /api/v1/agent/** leaked the global agent API key via the /api/v1/agent/install-info endpoint to unauthenticated users.
**Learning:** Overly permissive path matchers (wildcards) in SecurityConfig can inadvertently expose sensitive "internal" or "management" endpoints that were assumed to be protected or were added later without explicit security review.
**Prevention:** Always use explicit allow-lists for unauthenticated endpoints instead of broad wildcards. Any new endpoint added to a "public" path should be explicitly evaluated for security impact.
