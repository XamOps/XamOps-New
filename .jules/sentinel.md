# Sentinel Journal 🛡️

## 2025-05-14 - Information Leakage via Exception Messages (CWE-209)
**Vulnerability:** Multiple controllers and the global exception handler in `xamops-service` were found to be returning raw exception messages (`e.getMessage()`) directly in API responses.
**Learning:** Direct exposure of internal exception messages can reveal stack traces, database schema details, or third-party service internals to potential attackers, facilitating further exploitation.
**Prevention:** Always catch exceptions at the controller or global handler level and return generic, user-friendly error messages while logging the actual exception details internally for debugging.
