## 2026-07-01 - Restricted Agent API Exposure
**Vulnerability:** Broad `permitAll()` wildcard for `/api/v1/agent/**` in `SecurityConfig.java`.
**Learning:** This wildcard inadvertently exposed sensitive management endpoints like `/install-info` (which leaks the global agent API key) and `/db/config` (which leaks database credentials) to unauthenticated users. Only a subset of agent APIs actually perform internal API key validation.
**Prevention:** Always use explicit, narrow `AntPathRequestMatchers` specifying both paths and HTTP methods for public APIs. management or configuration endpoints should always fall under default session-based authentication unless explicitly required otherwise.
