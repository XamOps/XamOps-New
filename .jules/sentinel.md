## 2026-05-10 - IDOR in SecurityController
**Vulnerability:** Several endpoints in `SecurityController` (findings, export, prowler scan/status) allowed cross-tenant access by accepting an `accountId` and fetching data without validating ownership.
**Learning:** Multi-tenant isolation was missing at the controller layer. Even if data is cached, ownership must be verified before serving it to prevent unauthorized disclosure.
**Prevention:** Always include `clientId` in database queries for tenant-specific resources and perform ownership checks at the start of controller methods.
