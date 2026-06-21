# Sentinel Journal

## 2026-06-21 - CloudShell WebSocket Authentication and IDOR Protection
**Vulnerability:** The `/terminal` WebSocket endpoint for CloudShell was publicly accessible via `SecurityConfig` and lacked any internal authentication or authorization checks. A malicious user could connect to the WebSocket and access any cloud account shell by providing just an `accountId` in the query parameters (IDOR).
**Learning:** WebSocket endpoints (`TextWebSocketHandler`) in Spring do not automatically inherit session-based security unless explicitly configured in `SecurityFilterChain` AND verified within the handler using `session.getPrincipal()`.
**Prevention:** Always remove WebSocket endpoints from public permitAll whitelists and implement mandatory `Principal` verification and multi-tenant ownership validation (IDOR check) inside the `afterConnectionEstablished` method of the WebSocket handler.
