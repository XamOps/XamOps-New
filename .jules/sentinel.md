# Sentinel Journal - Critical Security Learnings

## 2025-05-14 - Agent Management IDOR Vulnerability Pattern
**Vulnerability:** Many management endpoints in `AgentController.java` (e.g., `/db/config`, `/db/register`, `/status`) lacked multi-tenant ownership validation. They accepted `cloudAccountId` or `clusterConfigId` as parameters but did not verify if the authenticated user belonged to the client that owns those resources.
**Learning:** The application uses a broad `permitAll()` for `/api/v1/agent/**` in `SecurityConfig.java` to accommodate agent ingest (which uses a global API key). However, this also exposed management endpoints that were intended for UI use via session authentication, without additional server-side ownership checks.
**Prevention:** All management endpoints that operate on specific accounts or clusters must explicitly validate resource ownership by comparing the `clientId` of the authenticated principal with the `clientId` of the resource owner. Use centralized helper methods for consistent validation.
