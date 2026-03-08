## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-03-01 - [HIGH] IDOR in TicketController
**Vulnerability:** The `createTicket` and `addTicketReply` endpoints in `xamops-service` accepted `creatorId` and `authorId` from the client request, allowing users to impersonate others by providing a different user's ID.
**Learning:** Trusting user-supplied IDs for identity without verifying them against the authenticated session is a classic IDOR vulnerability.
**Prevention:** Always use the authenticated session (e.g., `SecurityUtils.getCurrentUserId()`) to determine the identity of the user performing an action. Never allow clients to override their own identity via request parameters.

## 2026-03-08 - [CRITICAL] SQL Injection in GcpCostService
**Vulnerability:** User-controlled inputs like `tagKey` and `serviceName` were directly formatted into BigQuery SQL queries using `String.format`, allowing for SQL injection.
**Learning:** BigQuery queries are just as susceptible to injection as traditional SQL databases when built using string concatenation or formatting with unsanitized user input.
**Prevention:** Use the `QueryJobConfiguration` and `QueryParameterValue` classes from the Google Cloud BigQuery SDK to create parameterized queries. Avoid using `String.format` or concatenation to build query strings with dynamic values.
