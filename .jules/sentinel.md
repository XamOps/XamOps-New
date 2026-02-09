## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-02-09 - [HIGH] SQL Injection in BigQuery Queries
**Vulnerability:** Several methods in `GcpCostService` used `String.format` to build BigQuery SQL queries with user-controlled inputs (e.g., `tagKey`, `serviceName`). This allowed for potential SQL injection attacks.
**Learning:** BigQuery queries are often overlooked for SQL injection. Even though it's a data warehouse, injection can lead to unauthorized data access or resource exhaustion.
**Prevention:** Use `QueryJobConfiguration` with parameterized queries (`addNamedParameter`) instead of string formatting or concatenation.
