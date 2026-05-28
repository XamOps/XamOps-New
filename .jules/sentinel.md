## 2026-05-28 - Multi-tenant Isolation and Information Leakage Prevention in Disk Rightsizing
**Vulnerability:** Insecure Direct Object Reference (IDOR) and Sensitive Information Disclosure in EBS, GCP, and Azure Disk Rightsizing endpoints.
**Learning:** Cloud account identifiers (AWS Account ID, GCP Project ID, Azure Subscription ID) were being used in paths without verifying that they belong to the authenticated user's client. Additionally, raw exception messages were returned in API responses.
**Prevention:** Always use a centralized helper like `resolveAccount` that validates resource ownership against the current user's `clientId`. Replace `e.getMessage()` in controllers with generic user-facing messages and log the full details for internal debugging.
