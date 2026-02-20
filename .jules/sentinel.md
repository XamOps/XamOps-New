## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-02-20 - [HIGH] SQL Injection in BigQuery Queries
**Vulnerability:** Dynamic SQL queries were constructed using `String.format` with user-influenced or external API data (like instance names and project IDs), creating a risk of SQL injection in BigQuery.
**Learning:** Even for analytical databases like BigQuery, string interpolation for query parameters is insecure. BigQuery supports named parameters via `QueryJobConfiguration`.
**Prevention:** Use `QueryJobConfiguration.addNamedParameter` and `QueryParameterValue` to securely pass all dynamic values to BigQuery queries.

## 2026-02-20 - [MEDIUM] Sensitive Data Exposure in Application Logs
**Vulnerability:** `CommonsRequestLoggingFilter` was configured to include request payloads and headers, which could lead to accidental logging of passwords, session tokens, and PII.
**Learning:** Default logging filters should be restrictive in non-development environments to prevent sensitive data leakage.
**Prevention:** Set `setIncludePayload(false)` and `setIncludeHeaders(false)` in logging filters for SIT/Production environments.
