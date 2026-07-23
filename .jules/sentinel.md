## 2026-07-23 - [S3 Browser Endpoint Exposure]
**Vulnerability:** S3BrowserController endpoints (/api/s3-browser/**) were exposed publicly and lacked class-level session authentication enforcement, bypassing the standard role-based access checks.
**Learning:** Endpoints that are not explicitly permitted or mapped in SecurityConfig.java can still be exposed or mistakenly bypassed when wildcard routing or other configurations are updated, making class-level method security annotations critical for defense-in-depth.
**Prevention:** Always enforce class-level @PreAuthorize("isAuthenticated()") on UI-facing controllers to prevent unauthorized endpoint access and perform reflection-based security unit tests to verify the presence of annotations automatically.
