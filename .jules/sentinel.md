## 2024-05-05 - IDOR in Cloud Account Controllers
**Vulnerability:** Insecure Direct Object Reference (IDOR) allowed authenticated users to access cloud account data (EKS, GCP Cloudmap) belonging to other tenants by providing their AWS account ID or GCP project ID in request parameters.
**Learning:** Controller methods often used internal helper methods like `getAccount(String accountId)` that performed repository lookups without verifying if the requesting user's `clientId` matched the account's owner.
**Prevention:** Always use a centralized authorization check (e.g., `SecurityUtils.checkAccountOwnership(account)`) after retrieving a multi-tenant resource by a user-supplied ID.
