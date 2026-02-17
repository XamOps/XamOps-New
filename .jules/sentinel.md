## 2026-02-02 - [CRITICAL] Command Injection in CloudShellSocketHandler
**Vulnerability:** User-controlled variables (`projectId` for GCP and `subscriptionId` for Azure) were directly formatted into shell command strings executed via `/bin/bash -c` inside a Docker container.
**Learning:** Using `String.format` to build shell commands with user-controlled input is dangerous, even if the command is executed within a container. It allows an attacker to escape the intended command and execute arbitrary shell commands.
**Prevention:** Always pass user-controlled input as environment variables or separate arguments when executing external processes. Avoid building shell command strings with unsanitized input.

## 2026-02-17 - [CRITICAL] Broken Access Control in SecurityConfig
**Vulnerability:** Numerous sensitive management UIs (e.g., `/user-manager.html`, `/terminal`) and orchestration APIs (e.g., `/api/xamops/k8s/**`, `/api/cicd/**`) were listed in the `permitAll()` section of `SecurityConfig.java`, making them accessible to unauthenticated users.
**Learning:** Broad `permitAll()` lists in Spring Security can easily lead to "Broken Access Control" if developers add new pages/APIs to the public list for convenience during development and forget to restrict them later.
**Prevention:** Follow the principle of least privilege. Only explicitly allow public access to the login page and static resources. All other application features and APIs should require authentication by default.
