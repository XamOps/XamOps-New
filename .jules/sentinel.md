## 2026-05-01 - Agent Management Multi-tenancy and Credential Leakage
**Vulnerability:** Lack of ownership validation and plaintext credential exposure in AgentController management endpoints.
**Learning:** Overly permissive security configurations (permitAll) on paths like /api/v1/agent/** can lead to critical IDOR vulnerabilities and sensitive data leakage if internal controller-level validation is not strictly enforced.
**Prevention:** Implement standardized ownership validation helpers that verify the authenticated user's clientId against the target resource's owner (e.g., CloudAccount). Redact sensitive fields (like passwords) in API responses and handle placeholder values during updates to prevent accidental credential corruption or leakage.
