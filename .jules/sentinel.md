## 2026-05-24 - IDOR in SecurityController
**Vulnerability:** Insecure Direct Object Reference (IDOR) across multiple security endpoints (`/findings`, `/export`, etc.) and information leakage via cache hits for unauthorized accounts.
**Learning:** Controller endpoints that accept account identifiers must always validate ownership against the authenticated user's `clientId` *before* any other operation, including cache lookups, to prevent cross-tenant data access.
**Prevention:** Use `cloudAccountRepository.findByAwsAccountIdAndClientId` to ensure requested resources belong to the session's client and return a generic 404 response on failure to mitigate resource enumeration.
