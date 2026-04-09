## 2026-04-09 - [IDOR in Cloud Account Management]
**Vulnerability:** Multiple endpoints in `SecurityController.java` and `AccountManagerController.java` allowed cross-tenant access and modification of cloud accounts by supplying an `accountId` or database `id` without verifying ownership.
**Learning:** In a multi-tenant system, authentication alone is insufficient; every request targeting a specific resource must be authorized by verifying that the resource's owner (Client ID) matches the authenticated user's Client ID.
**Prevention:** Implement a central authorization utility (e.g., `SecurityUtils.isAuthorizedForAccount`) and enforce it at the controller level for all endpoints that retrieve or mutate data based on user-supplied identifiers.
