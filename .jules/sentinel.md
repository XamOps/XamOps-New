# Sentinel Journal - Critical Security Learnings

## 2026-04-13 - [Hardening Agent API and Preventing IDOR]
**Vulnerability:** The `GET /api/v1/agent/install-info` endpoint was leaking the global agent API key because it was covered by a broad `permitAll()` whitelist in `SecurityConfig.java`. Additionally, management endpoints in `AgentController.java` lacked ownership checks, allowing potential IDOR attacks.
**Learning:** Broad whitelists for API paths (e.g., `/api/v1/agent/**`) can inadvertently expose sensitive data and bypass session-based authentication for management actions.
**Prevention:** Use specific endpoint matchers in security configurations and always implement ownership validation (comparing resource owner to authenticated user) in REST controllers.
