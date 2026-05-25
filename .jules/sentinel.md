## 2024-05-25 - IDOR in Cloud Resource Management Controllers
**Vulnerability:** Insecure Direct Object Reference (IDOR) / Broken Object Level Authorization (BOLA) in `EbsDiskRightsizingController`. The controller accepted `accountId` and `policyId` directly from the URL without verifying ownership.
**Learning:** Controllers often rely on service-level helpers (like `resolveAccount`) for resource fetching. Securing these central helpers is an efficient way to enforce multi-tenant isolation across multiple endpoints.
**Prevention:** Always validate that the requested resource (CloudAccount, Policy, etc.) belongs to the authenticated user's `clientId`. Map "Access Denied" errors to `404 Not Found` to prevent resource enumeration.
