## 2026-07-19 - EKS Autoscaling Security Hardening and IDOR Fix
**Vulnerability:** IDOR (Insecure Direct Object Reference) and Authentication Bypass on sensitive EKS Autoscaling endpoints.
**Learning:** Endpoints mapped under `/api/eks/autoscaling/**` were in the public `permitAll()` list in `SecurityConfig.java`, allowing unauthenticated users to access or modify EKS autoscaling policies. Furthermore, the controller lacked `validateAccountOwnership` to verify client-level ownership.
**Prevention:** Remove wildcard patterns like `/api/eks/autoscaling/**` from public access lists. Implement explicit ownership validation checks mapping authenticated `clientId` against the target `cloudAccountId` to block unauthorized IDOR attempts.
