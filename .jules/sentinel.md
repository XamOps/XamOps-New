## 2026-07-06 - Remediate Hardcoded SuperAdmin Credentials
**Vulnerability:** Hardcoded credentials and sensitive data exposure in logs.
**Learning:** Initializing critical administrative accounts with hardcoded passwords in `CommandLineRunner` implementations creates a long-lived security risk and leaks secrets via version control. Printing these credentials to logs further exacerbates the issue.
**Prevention:** Use Spring `@Value` or `@ConfigurationProperties` to externalize secrets to environment variables. Ensure logs never contain plaintext passwords or other PII/secrets.
