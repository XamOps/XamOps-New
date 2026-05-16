## 2026-05-16 - Broken Object Level Authorization (IDOR) in SecurityController
**Vulnerability:** Endpoints in `SecurityController` fetched cloud account security findings based on a user-provided `accountId` without validating ownership.
**Learning:** Even when using a multi-tenant database, endpoints that accept resource identifiers as parameters must explicitly validate that the identifier belongs to the authenticated user's tenant (client) to prevent unauthorized cross-tenant data access.
**Prevention:** Always validate resource ownership at the controller level using `SecurityUtils` and tenant-scoped repository methods (e.g., `findByAwsAccountIdAndClientId`) before processing requests or checking caches.
