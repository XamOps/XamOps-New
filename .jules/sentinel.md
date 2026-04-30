## 2024-04-30 - Agent Management IDOR and Secret Leakage
**Vulnerability:** Agent management endpoints in `AgentController` lacked ownership validation, allowing authenticated users to view or modify agents belonging to other clients. Additionally, the `loadDbAgentConfig` endpoint leaked database passwords in plaintext.
**Learning:** Broad `permitAll()` in `SecurityConfig.java` for `/api/v1/agent/**` made internal controller-level ownership validation critical for any endpoint not strictly used by automated agents.
**Prevention:** Implement `validateOwnership` helpers that check the authenticated user's `clientId` against the resource owner's `clientId`. Always redact sensitive fields like passwords before returning configuration to the UI.

## 2024-04-30 - Credential Exposure in Process Lists
**Vulnerability:** `ProwlerService.java` passed Azure Service Principal secrets as command-line arguments to the Prowler CLI. These secrets would be visible to any user or monitoring tool capable of listing system processes (e.g., `ps aux`).
**Learning:** CLI tools often accept credentials via both arguments and environment variables. The latter is significantly more secure as environment variables are not typically exposed in process listings.
**Prevention:** Use environment variables for sensitive authentication data when calling external CLI tools. For Prowler v5+, utilize the `--sp-env-auth` flag to specifically enable secure environment-based authentication for Azure.
