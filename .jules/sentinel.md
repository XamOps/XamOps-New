## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-02-28 - [HIGH] IDOR in Ticket and Reply creation
**Vulnerability:** Ticket and Reply creation endpoints in `xamops-service` relied on `creatorId` and `authorId` provided in the request body or as request parts. This allowed authenticated users to spoof the identity of the creator or author of a ticket/reply by providing a different user's ID.
**Learning:** Even when authenticated, client-provided identity identifiers should never be trusted for write operations if the identity can be resolved from the server-side security context.
**Prevention:** Always enforce the identity of the user from the authenticated security context (e.g., `SecurityContextHolder`) rather than trusting user-supplied IDs in the request.
