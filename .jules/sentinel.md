## 2026-04-18 - IDOR Prevention and API Whitelist Hardening
**Vulnerability:** Insecure Direct Object Reference (IDOR) in Security and Agent management endpoints, and an overly broad API whitelist.
**Learning:** The `/api/v1/agent/**` whitelist allowed any request to bypass session authentication, including management APIs like `/db/register`. Furthermore, endpoints fetching security findings lacked ownership checks, allowing cross-tenant data access if a valid AWS account ID was known.
**Prevention:** Always use specific path patterns in security whitelists (e.g., `/api/v1/agent/ingest/**`) and implement a centralized ownership check (e.g., `SecurityUtils.isAuthorizedForAccount`) for all resource-accessing REST endpoints.
