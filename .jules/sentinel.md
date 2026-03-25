## 2026-03-25 - [Broken Access Control via permitAll()]
**Vulnerability:** Numerous sensitive internal dashboards and APIs (SonarQube, User Manager, Kubernetes, AutoSpotting) were explicitly whitelisted in `SecurityConfig.java` using `permitAll()`.
**Learning:** Legacy configurations often accrue redundant or insecure `permitAll()` rules during development that are never cleaned up for production.
**Prevention:** Audit `SecurityFilterChain` regularly; use a "deny by default" approach and only explicitly whitelist truly public assets (CSS, JS, login, health checks).
