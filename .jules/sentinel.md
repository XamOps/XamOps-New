# 🛡️ Sentinel Security Journal

## 2025-05-14 - Hardcoded Secrets in Configuration Files
**Vulnerability:** Multiple sensitive credentials, including database passwords, AI provider API keys, and encryption secrets, were hardcoded directly in `application.properties` and environment-specific property files (`application-prod.properties`, etc.).
**Learning:** Hardcoding secrets in configuration files is a common shortcut during development that often persists into production, leading to credential exposure in version control and increasing the risk of unauthorized access.
**Prevention:** Always use Spring property placeholders (e.g., `${VAR:DEFAULT}`) for sensitive values. Crucially, the `DEFAULT` value must not be the actual secret, but a generic placeholder (like `CHANGE_ME`) to ensure the secret is truly removed from version control. Provide actual values via environment variables or a secure secret management system at runtime.

## 2025-05-14 - Information Leakage via Exception Messages
**Vulnerability:** `LlmProxyController` was returning raw exception messages (`e.getMessage()`) in its error responses, potentially exposing internal implementation details or sensitive path information to clients.
**Learning:** Returning direct exception messages to users is a CWE-209 violation. It provides attackers with insights into the application's internal workings, which can be leveraged for further exploits.
**Prevention:** Always return generic, user-friendly error messages in API responses and log the detailed exception for internal debugging.
