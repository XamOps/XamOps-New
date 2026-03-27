## 2026-03-27 - Hardened Security Configuration and Secret Management
**Vulnerability:** Hardcoded secrets in properties files, overly permissive whitelisting in SecurityConfig, and sensitive data exposure in logs.
**Learning:** Default values in environment variable placeholders (e.g., ${VAR:SECRET}) are still committed to the repository and can be exploited. Whitelisting entire API trees (e.g., /api/v1/agent/**) increases the attack surface for internal management endpoints.
**Prevention:** Remove default values for sensitive placeholders. Use specific matchers for required endpoints and methods. Disable header and payload logging in production filters to prevent credential leakage.
