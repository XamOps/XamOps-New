## 2026-06-23 - Management API Exposure & Information Leakage
**Vulnerability:** Broad `permitAll` wildcards (CWE-284) and raw exception leakage (CWE-209).
**Learning:** The `/api/v1/agent/**` permitAll rule inadvertently exposed sensitive management endpoints like `/install-info` (leaked global API key) and `/db/config` (potentially leaked DB credentials). Additionally, the global exception handler was returning `ex.getMessage()` to clients.
**Prevention:** Always use explicit AntPathRequestMatchers for public APIs instead of broad wildcards. Ensure all catch blocks and global handlers return sanitized, generic messages to clients while logging full details internally.
