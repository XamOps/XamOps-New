
## 2026-04-29 - Agent Management IDOR and Sensitive Data Exposure
**Vulnerability:** IDOR in AgentController endpoints (/db/register, /db/config, /db/status) allowing any authenticated user to manage DB agents of other tenants by providing their cloud account ID. Also, database passwords were leaked in plaintext in the /db/config GET endpoint.
**Learning:** Endpoints that use external identifiers (like AWS Account ID) for resource lookup must explicitly verify ownership against the authenticated user's client context.
**Prevention:** Implement a standard validateOwnership helper that checks clientId against the target resource's owner and enforce it on all tenant-specific management endpoints. Redact passwords in API responses unless absolutely necessary.
