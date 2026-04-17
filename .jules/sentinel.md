## 2026-04-17 - IDOR in SecurityController
**Vulnerability:** Broken Object Level Authorization (BOLA) / IDOR in `SecurityController`.
**Learning:** Endpoints taking `accountId` (as a String or Long) often lack ownership checks, relying on the frontend to only send valid IDs. This allows any authenticated user to access findings or trigger scans for any account by guessing the ID.
**Prevention:** Always implement an ownership check using `SecurityUtils.isAuthorizedForAccount` in any controller method that accepts a resource identifier (like `accountId`). Ensure the check happens *before* any business logic or cache hits.
