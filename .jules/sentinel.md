## 2026-05-11 - Secure agent info and LLM proxy errors
**Vulnerability:** Information leakage of the global agent API key via an unauthenticated endpoint (`/api/v1/agent/install-info`) and leakage of internal error details in `LlmProxyController` through raw exception messages.
**Learning:** Endpoints providing configuration or installation details are often overlooked in security scans but can expose critical credentials. Similarly, returning raw exception messages to the client is a common anti-pattern that facilitates reconnaissance.
**Prevention:** Always enforce authentication for endpoints returning sensitive configuration and use generic, safe error messages for client-facing responses while logging the full details server-side.
