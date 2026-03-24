## 2026-03-24 - SQL Injection in BigQuery Queries
**Vulnerability:** User-controlled parameters (`tagKey`, `serviceName`, `labelKey`) were directly interpolated into BigQuery SQL strings using `String.format` in `GcpCostService.java`.
**Learning:** Developers may favor `String.format` for quick query construction, bypassing the parameterized query features provided by the BigQuery SDK (`QueryParameterValue`). This pattern was found across multiple cost-reporting methods.
**Prevention:** Enforce the use of `QueryJobConfiguration.Builder.addNamedParameter()` for all dynamic query values. Any use of `String.format` or `+` concatenation for SQL parameters should be flagged during code review.
