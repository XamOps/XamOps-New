## 2026-04-25 - Secured Prowler Azure Scan Credentials
**Vulnerability:** Azure client secrets were passed as command-line arguments to the Prowler executable, exposing them in process lists and application logs.
**Learning:** Passing secrets via CLI flags like `--client-secret` is insecure as command arguments are visible to other users on the system and often logged by monitoring tools.
**Prevention:** Utilize environment variables and specialized authentication flags (e.g., `--sp-env-auth` for Prowler) to securely transmit credentials to external processes.
