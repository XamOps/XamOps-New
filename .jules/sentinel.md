## 2026-04-15 - [IDOR in Security Findings]
**Vulnerability:** Insecure Direct Object Reference (IDOR) allowed any authenticated user to access security findings for any AWS account by providing its account ID in the query parameters.
**Learning:** Controller endpoints often lack ownership checks even when they are protected by session authentication. Relying solely on 'is authenticated' is insufficient for multi-tenant safety.
**Prevention:** Implement a central authorization helper (e.g., `SecurityUtils.isAuthorizedForAccount`) and use it in all controller endpoints that accept resource IDs or external identifiers.
