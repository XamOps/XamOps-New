## 2026-04-04 - Narrowing Agent API Whitelist and CORS Hardening
**Vulnerability:** Broad wildcard whitelist for `/api/v1/agent/**` allowed unauthenticated access to sensitive endpoints like `/api/v1/agent/install-info`, which leaked the global agent API key.
**Learning:** Whitelisting entire path hierarchies for unauthenticated access (e.g., for agents using API keys) is dangerous. Informational or management endpoints in the same hierarchy may expose secrets intended for the server-to-agent trust relationship.
**Prevention:** Always use specific, granular endpoint patterns in `SecurityConfig` for `permitAll()` rules instead of broad wildcards.

**Vulnerability:** Use of `@CrossOrigin(origins = "*")` in `DevOpsScriptController`.
**Learning:** Controller-level CORS annotations can bypass global restricted CORS policies and are often left as remnants of development.
**Prevention:** Enforce CORS at the global configuration level and audit controllers for permissive overrides.
