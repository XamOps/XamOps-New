## 2026-03-12 - BigQuery SQL Injection in Billops
**Vulnerability:** SQL injection in GCP cost retrieval methods within the `billops` module. Unsanitized user-supplied `serviceName` and dynamic date strings were formatted directly into BigQuery SQL queries.
**Learning:** BigQuery queries are just as susceptible to SQL injection as traditional relational databases when using string concatenation or formatting. The `com.google.cloud.bigquery` SDK provides `QueryJobConfiguration` and `QueryParameterValue` to support parameterized queries.
**Prevention:** Always use parameterized queries (named or positional parameters) when building BigQuery SQL statements. Avoid `String.format` or concatenation for any part of the query that includes dynamic data.
