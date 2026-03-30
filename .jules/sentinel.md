## 2026-03-30 - BigQuery SQL Injection via String.format
**Vulnerability:** SQL Injection in BigQuery queries.
**Learning:** BigQuery queries were being constructed using `String.format` for parameters like `tagKey` and dates. While some identifiers (like table names) must be formatted, values should always be parameterized using `QueryParameterValue` to prevent injection.
**Prevention:** Use `QueryJobConfiguration.newBuilder(query).addNamedParameter(...)` for all user-supplied or dynamic values in BigQuery queries.
