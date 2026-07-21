## 2026-07-21 - [AutoSpotting IDOR Hardening and Class-Level Authorization]
**Vulnerability:** IDOR (Insecure Direct Object Reference) and missing authorization on all `/api/autospotting/**` endpoints. Unauthenticated users (due to permitAll config) or authenticated users belonging to different client accounts could query or manipulate AWS AutoSpotting configuration, costs, ASGs, and event history for arbitrary AWS accounts by changing the `accountId` in the request path.
**Learning:** Broad `permitAll()` configurations on sensitive cloud management APIs (like `/api/autospotting/**`) can bypass standard security filters. Handlers must use method-level security (e.g. `@PreAuthorize("isAuthenticated()")`) and validate client-level ownership of the target cloud accounts.
**Prevention:**
1. Use Spring Security `@PreAuthorize("isAuthenticated()")` at the class-level for UI-facing API controller classes to enforce session-based authentication.
2. Implement a centralized `@ModelAttribute` method with `@PathVariable(value = "accountId", required = false)` in controllers with multiple account-based endpoints to enforce client ownership validation dynamically on all matching endpoints in under 50 lines.
3. Establish a standard MockMvc controller security test pattern utilizing `SecurityContextHolder` mock authentication instead of fragile static Mockito mock makers.
