## 2026-03-11 - SQL Injection in BigQuery Queries
**Vulnerability:** BigQuery queries in `GcpCostService.java` were constructed using `String.format` with user-supplied inputs like `tagKey`, `serviceName`, and date ranges, making them susceptible to SQL injection.
**Learning:** The use of string concatenation or formatting for query construction is a common anti-pattern, often chosen for perceived simplicity, but it bypasses the safety provided by parameterized queries.
**Prevention:** Always use `QueryJobConfiguration` with named parameters (`addNamedParameter`) when including external variables in BigQuery queries. Avoid `String.format` or `+` for query construction except for non-parameterizable identifiers (like table names), which must be strictly validated or internal.
