## 2026-05-04 - [Agent Management IDOR and Secret Leakage]
**Vulnerability:** Several management endpoints in `AgentController` (e.g., `/db/config`, `/db/register`, `/db/status`) were vulnerable to IDOR, allowing any authenticated user to access or modify configurations for any cloud account. Additionally, database passwords were returned in plaintext.
**Learning:** Broad `permitAll()` rules in `SecurityConfig.java` for `/api/v1/agent/**` required strict internal controller-level ownership validation that was missing.
**Prevention:** Always implement ownership validation (comparing authenticated user's `clientId` with resource owner) for any management endpoint, especially when standard URL patterns are partially exposed for public ingest paths.
