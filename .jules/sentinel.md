# Sentinel Security Journal

## 2026-05-14 - Password Reset Vulnerabilities
**Vulnerability:** Predictable OTPs (using java.util.Random), sensitive data leakage to stdout (System.out.println), and information exposure in error responses (e.getMessage()).
**Learning:** Legacy or rapid-prototype code often prioritizes functionality over security, using basic utilities without considering cryptographic strength or operational security (logging/info-leakage).
**Prevention:** Always use `SecureRandom` for secrets/tokens, implement structured SLF4J logging (avoid stdout), and return generic error messages to clients to prevent internal state leakage and user enumeration.
