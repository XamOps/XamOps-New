## 2026-07-26 - [Wildcard Endpoint Whitelisting and Sensitive Internal Endpoint Exposure]
**Vulnerability:** Broad wildcard permitAll() patterns in Spring Security (e.g. `/api/v1/agent/**`) can result in silent, unintended exposure of sensitive internal/dashboard endpoints that exist under the same namespace, such as database credential handlers (`/db/config`) and administrative secrets providers (`/install-info`).
**Learning:** Security whitelists on URL prefix trees often capture a mix of both agent-facing and user-facing controllers. When a namespace is declared public for integration agents, any nested dashboard helper mapping is accidentally exposed to public access, bypassing session authentication and leading to catastrophic information disclosure (e.g., global API key leakage, database connection passwords).
**Prevention:**
- Enforce method-level or class-level `@PreAuthorize("isAuthenticated()")` or role-based controls on all UI-facing endpoints, even if nested under wildcard prefixes.
- Utilize reflection-based unit tests to dynamically inspect and enforce security annotations on target controllers, preventing future developer commits from dropping critical authorization hooks.
- Keep agent/machine-to-machine public paths narrow and separated from admin/UI endpoints.
