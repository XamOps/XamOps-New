## 2026-05-30 - [Information Leakage in Error Messages]
**Vulnerability:** Raw exception messages leaked via `ex.getMessage()` in the global exception handler.
**Learning:** Returning internal implementation details in API responses can expose database structures or internal logic, aiding attackers.
**Prevention:** Always return generic user-facing messages in global exception handlers and log the actual exception details on the server for debugging.
