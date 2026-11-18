# Ledger reconciliation and UNKNOWN prices

Owner: AutoSpotting on-call. Design: `design-ledger.md` §4, §6.3, §7. This runbook is completed in Phase 25 (variance
bands, bill imports); Phase 17 adds the UNKNOWN-price diagnosis below, because the AWS ledger writer is live in
shadow from Phase 17: it measures and stores savings, and nothing is invoiced.

## When it fires

- Alarm `autospotting-ledger-unknown-price-ratio-<env>` (Ticket): entries written with `tier = UNKNOWN` are over the
  threshold share of all entries in 24 h (default 5%, parameter `LedgerUnknownRatioThreshold`).
- Alarm `autospotting-ledger-writer-missed-run-<env>` (Page): `autospotting.ledger.last_success_age{job=ledger-writer:aws}`
  is over 26 h. The meter reads 0 while the writer is switched off, so a dark writer never pages.

## Diagnose an UNKNOWN price

An UNKNOWN entry carries zero money and is never billable (design-ledger.md §6.3). Its `unknown_inputs` names the cause.

```sql
SELECT period_start::date AS day, group_id, unknown_inputs, count(*)
FROM savings_ledger_entry e JOIN savings_ledger_run r ON r.id = e.run_id
WHERE e.tier = 'UNKNOWN' AND e.provider = 'AWS' AND e.period_start >= now() - interval '7 days'
  AND NOT EXISTS (SELECT 1 FROM savings_ledger_run s WHERE s.supersedes_run_id = r.id)
GROUP BY 1, 2, 3 ORDER BY 1 DESC;
```

| `unknown_inputs` | Meaning | Check | Fix |
|---|---|---|---|
| `MISSING_SPOT_PRICE` | No eligible Spot snapshot for the group's (account, AZ, type, platform) in the window | `SELECT max(effective_from), max(fetched_at) FROM spot_price_snapshot WHERE source='AWS_SPOT_HISTORY' AND zone = '<cloudAccountId>/<az>' AND sku_or_type = '<type>'`. A gap means the hourly `price-snapshot` job failed for that pool (look for `autospotting.cloud.errors{api=DescribeSpotPriceHistory}`), or the account's role lost `ec2:DescribeSpotPriceHistory`. A snapshot fetched after the window with no stated effective time cannot price the past (§4.4). | Fix the cause. The next successful fetch stores the rows. Request a `PRICE_RESOLVED` rerun for the affected days so a new run supersedes the UNKNOWN entry before the month closes. |
| `MISSING_OD_PRICE` | No eligible list price for the ran type or the pre-enable type | `SELECT max(effective_from) FROM spot_price_snapshot WHERE source='AWS_PRICE_LIST' AND sku_or_type = '<type>' AND platform = '<platform>' AND region = '<region>'`. The Price List job runs daily at 01:15 UTC; a platform with no list mapping (SQL editions on Spot) is never priced. | As above. An unmapped platform stays UNKNOWN by design. |
| `MISSING_PLATFORM`, `MISSING_WEIGHT` | The observer could not read the platform or capacity weight of an instance | The instance interval row in `spot_usage_interval`. | Fix the group's launch template or override weights; the next observe sweep reads them. |
| `MISSING_DESIRED`, `MISSING_SNAPSHOT` | No capacity sample, or no original-configuration snapshot | `spot_group_capacity_sample` and `spot_group.snapshot_id` for the group. | Wait one observe sweep (5 min). A group with no snapshot is a lifecycle defect: escalate to the group's owner. |

Nothing is interpolated, back-filled or defaulted by hand. Before a month closes only a run with newly measured inputs
supersedes an UNKNOWN entry; after close only an adjustment can (design-ledger.md §6.3).

## Writer missed run

1. Is the writer meant to be on? `autospotting.ledger.aws.writer-enabled` (prod only) and no engaged `LEDGER:aws` switch.
2. Is the job lease stuck? `SELECT * FROM autospotting_job_lease WHERE name = 'ledger-writer:aws'` (`last_completed_at`, `expires_at`).
3. Look for `LeaseLostException` or `autospotting.job.skipped{job=ledger-writer:aws}` in the logs.
4. A rerun is safe: an equal-input rerun creates no rows (design-ledger.md §13.2 step 4).

Rollback of the shadow writer: engage switch `LEDGER:aws`, or set the property false. Nothing here ever invoices.
