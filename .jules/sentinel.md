# Sentinel Security Journal

## 2025-05-22 - Hardcoded Secrets in Properties Files
**Vulnerability:** Numerous hardcoded secrets (passwords, API keys, encryption keys) were found across multiple Spring Boot properties files (`application.properties`, `application-local.properties`, `application-prod.properties`, etc.).
**Learning:** Development and environment-specific properties files often accumulate hardcoded secrets over time, especially when placeholders are not used from the start. Duplicate entries (e.g., `cloudidr.auth.password`) at the bottom of the file can override intended configurations or lead to accidental leakage if only one instance is remediated.
**Prevention:** Use Spring property placeholders `${VAR_NAME:DEFAULT_VALUE}` for all sensitive information and ensure secrets are managed via environment variables or a dedicated secret manager in all environments. Perform periodic scans for hardcoded secrets in configuration files.
