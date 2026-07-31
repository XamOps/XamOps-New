# Sentinel Security Journal 🛡️

## 2026-07-31 - [HIGH] Enforced Authentication on Cloud AutoSpotting Controllers
**Vulnerability:** Public exposure of sensitive cloud AutoSpotting endpoints. The path matchers `/api/autospotting/**`, `/api/azure/autospotting/**`, and `/api/gcp/autospotting/**` were registered as `permitAll()` in `SecurityConfig.java`, allowing unauthenticated actors to retrieve cloud cost summaries, list ASGs/VMSSs/MIGs, and enable/disable spot VM automation.
**Learning:** Legacy integrations and utility APIs are often added to `permitAll()` wildcard patterns to avoid complex frontend/client handshake configurations during initial development phases, which accidentally exposes critical operations to public internet bypass.
**Prevention:** Avoid registering broad wildcard patterns in `permitAll()`. Ensure class-level `@PreAuthorize("isAuthenticated()")` defenses are applied to all UI-facing cloud management controllers. Verify configuration constraints via reflection-based unit tests.
