## 2026-05-26 - IDOR in EbsDiskRightsizingService
**Vulnerability:** Insecure Direct Object Reference (IDOR) in policy management and account resolution.
**Learning:** Service methods like `updatePolicy` and `deletePolicy` were directly using IDs without verifying that the resource belonged to the authenticated user's `clientId`. Additionally, `resolveAccount` was fetching accounts by provider ID without organization-level isolation.
**Prevention:** Always validate resource ownership against the authenticated principal's `clientId` in service-level resolution methods. Use generic error messages ("Account not found or access denied") to prevent resource enumeration.
