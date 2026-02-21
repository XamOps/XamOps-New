## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-02-21 - [HIGH] SQL Injection in BigQuery Queries
**Vulnerability:** BigQuery SQL queries in `GcpCostService` and `GcpOptimizationService` were constructed using `String.format` or string concatenation, incorporating user-controlled inputs like `tagKey` and `serviceName` directly into the query strings.
**Learning:** Even specialized SQL dialects like BigQuery are vulnerable to injection if queries are built via string manipulation. Developers often overlook this for non-relational or cloud-based data warehouses.
**Prevention:** Use the `QueryJobConfiguration` and `QueryParameterValue` APIs provided by the Google Cloud BigQuery SDK to create parameterized queries. Never use `String.format` or concatenation for dynamic values in SQL statements.
