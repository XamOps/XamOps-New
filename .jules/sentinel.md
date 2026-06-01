# Sentinel Security Journal 🛡️

## 2025-02-12 - Hardcoded production database credentials in dead code
**Vulnerability:** Hardcoded database IP (3.110.102.128), username (app_user), and password (AppPassword123) were found in `MultiTenantDataSourceConfig.java`.
**Learning:** These credentials were part of an experimental multi-tenant routing implementation that was never fully activated as a Spring Bean, leaving them as "dead" but accessible secrets in the source code.
**Prevention:** Always use `@Value` or environment variables for infrastructure secrets, even during prototyping. Use static analysis tools to detect hardcoded secrets and dead code before commit.
