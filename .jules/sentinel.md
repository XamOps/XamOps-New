## 2026-05-21 - Over-permissive Agent API Wildcards
**Vulnerability:** Global Agent API key exposure via unauthenticated management endpoints.
**Learning:** Broad `permitAll("/api/v1/agent/**")` rules in `SecurityConfig.java` allowed unauthenticated access to `/api/v1/agent/install-info`, which returns the `globalAgentApiKey` used by all agents to authenticate. This allowed anyone on the internet to retrieve the key and spoof agent data.
**Prevention:** Avoid using broad wildcard patterns for security exemptions in sensitive API namespaces. Use explicit `AntPathRequestMatcher` entries for only the required public endpoints, preferably specifying the allowed HTTP method (e.g., `POST` for ingest) to further minimize the attack surface.
