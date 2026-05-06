## 2024-05-24 - Agent Management IDOR and Secret Exposure
**Vulnerability:** Multiple endpoints in `AgentController` (including those exposing the global agent API key and database configurations) were accessible without ownership checks or authentication due to a broad `permitAll()` rule in `SecurityConfig`.
**Learning:** Broadly permissive security configurations for API-key-based endpoints (like agents) can accidentally expose management endpoints to unauthorized users if ownership is not explicitly validated in the controller.
**Prevention:** Always implement explicit ownership validation (Client ID check) for any management endpoint, especially when path-based security is relaxed.
