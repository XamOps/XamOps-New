## 2026-04-21 - IDOR Protection Pattern for Multi-Tenant Cloud Accounts
**Vulnerability:** Several API endpoints in `SecurityController` and `AccountManagerController` allowed users to access or modify cloud accounts by ID without verifying if the account belonged to their client (IDOR). Additionally, the Agent API whitelist was overly broad, exposing sensitive management info like the global API key.
**Learning:** In a multi-tenant application, relying on `@PathVariable` or `@RequestParam` for resource IDs without ownership checks against the authenticated principal (`ClientUserDetails`) leads to critical IDOR vulnerabilities.
**Prevention:**
1. Always use `@AuthenticationPrincipal` to get the current user's `clientId`.
2. Encapsulate ownership logic in a central utility (e.g., `SecurityUtils.isAuthorizedForAccount`) that checks for SuperAdmin status or matching `clientId`.
3. Narrow security whitelists (e.g., in `SecurityConfig`) to the absolute minimum required paths, using specific patterns instead of broad wildcards like `/**` for sensitive API groups.
