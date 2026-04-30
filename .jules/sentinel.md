## 2024-04-30 - Agent Management IDOR and Secret Leakage
**Vulnerability:** Agent management endpoints in `AgentController` lacked ownership validation, allowing authenticated users to view or modify agents belonging to other clients. Additionally, the `loadDbAgentConfig` endpoint leaked database passwords in plaintext.
**Learning:** Broad `permitAll()` in `SecurityConfig.java` for `/api/v1/agent/**` made internal controller-level ownership validation critical for any endpoint not strictly used by automated agents.
**Prevention:** Implement `validateOwnership` helpers that check the authenticated user's `clientId` against the resource owner's `clientId`. Always redact sensitive fields like passwords before returning configuration to the UI.
