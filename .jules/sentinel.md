## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-02-10 - [HIGH] SQL Injection in BigQuery Queries
**Vulnerability:** Several methods in `GcpCostService` and `GcpOptimizationService` were using `String.format` to build BigQuery SQL queries, concatenating user-controlled or external inputs (e.g., `tagKey`, `serviceName`, `instanceName`).
**Learning:** BigQuery queries should always use parameterized `QueryJobConfiguration` with named parameters (`@paramName`) to prevent SQL injection. Identifiers like table names or columns that cannot be parameterized must be strictly validated or sourced from trusted internal logic.
**Prevention:** Refactor all database/BigQuery interaction layers to use parameterized queries by default. Avoid `String.format` or string concatenation for any query component other than fixed identifiers.
