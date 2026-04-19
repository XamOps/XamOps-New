## 2025-04-19 - [IDOR & Sensitive Data Exposure in Agent Management]
**Vulnerability:** Broken Access Control (IDOR) on agent management endpoints and exposure of plaintext database passwords.
**Learning:** Broad `permitAll()` whitelists in security configurations can inadvertently expose management endpoints that were intended for authenticated users. Additionally, returning raw entity objects often leaks sensitive fields like passwords.
**Prevention:** Use narrow whitelists for public/unauthenticated endpoints. Always implement explicit ownership checks in controllers for resource-specific actions. Redact sensitive fields in DTOs or controller responses.
