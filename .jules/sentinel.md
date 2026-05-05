## 2026-05-05 - Agent Management BOLA and Credential Exposure
**Vulnerability:** Broken Object Level Authorization (BOLA/IDOR) on DB Agent management endpoints and plaintext credential exposure in configuration responses.
**Learning:** Broad `permitAll()` paths in `SecurityConfig.java` (like `/api/v1/agent/**`) bypass standard session filters, making manual ownership validation inside controllers essential for any endpoint that accepts `accountId` or `clusterId`.
**Prevention:** Always implement a `validateOwnership` helper in controllers handling public paths and ensure sensitive fields like `dbPassword` are redacted in response DTOs or Maps before returning to the UI.
