## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-02-04 - [HIGH] BigQuery SQL Injection in Cost Services
**Vulnerability:** Google Cloud BigQuery queries in 'GcpCostService' and 'GcpOptimizationService' were constructed using 'String.format' with user-controlled input (e.g., tag keys, service names, instance names), presenting a SQL injection risk.
**Learning:** BigQuery queries are just as vulnerable to SQL injection as traditional SQL databases. Developers often forget to parameterize these queries when using the GCP SDK.
**Prevention:** Always use 'QueryJobConfiguration' with 'QueryParameterValue' for parameterized queries in BigQuery. For schema identifiers like column names that cannot be parameterized, use a strict whitelist.
