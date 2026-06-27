# Sentinel Security Journal

## 2026-06-25 - Mitigating Broad API Exposure in SecurityConfig
**Vulnerability:** Overly permissive AntPathRequestMatcher wildcard (`/api/v1/agent/**`) in `SecurityConfig.java` allowed unauthenticated access to sensitive management endpoints, including `/install-info` (leaking the global API key) and `/db/config` (exposing plaintext database credentials).
**Learning:** Broad wildcards in security configurations often inadvertently expose management or diagnostic endpoints that were never intended to be public. Even if some endpoints within the path implement their own authentication (like API key checks), others might not, creating a significant security gap.
**Prevention:** Use an explicit allow-list for public or API-key-authenticated endpoints instead of broad wildcards. Management endpoints should always default to requiring a full user session unless there is a documented and secured reason otherwise.
