# ComplianceOps Resource-Weighted Scoring Design

**Date:** 2026-07-16
**Feature area:** SecOps → ComplianceOps
**Personas:** Customers viewing compliance %; internal team explaining ComplianceOps vs XamSecure discrepancy

---

## Problem

ComplianceOps (`frontend-app/src/pages/ComplianceOpsPage.tsx`) scores each compliance framework with an all-or-nothing control rule: a control is marked failed for the entire account if even one resource fails it, regardless of how many other resources pass. At real-account scale (hundreds of resources across 4 scanned regions), this makes it near-mathematically-certain that any broadly-applicable control has at least one exception somewhere — so scores sit near 0% for large frameworks (NIST 800-53: 3%, FedRAMP: 3%, NIST CSF: 0%) and barely move even as customers remediate findings.

This also creates a jarring, unexplained contradiction inside the same product: XamSecure's Upwind-based "Frameworks & rules" tab shows 80–99% for similarly-named frameworks (CIS: 86%, PCI-DSS: 83%) on the same account, because Upwind's methodology is resource-weighted rather than strict per-control.

## Decision

Switch the ComplianceOps per-framework `score` to a **flat resource-level average**:

```
score = round(totalPassingFindings / (totalPassingFindings + totalFailingFindings) * 100)
```

counted across every PASS/FAIL finding mapped to that framework (not deduplicated by control). A control checked against 500 resources naturally carries more weight in the score than one checked against 2 resources — this matches actual risk exposure and means the score visibly improves as customers fix individual findings, rather than staying pinned near 0% until every last exception on a control is gone.

## What changes

`frameworkScores` useMemo in `ComplianceOpsPage.tsx` (currently lines 429-462):
- Add per-framework resource-level pass/fail counters (`resourcePass`, `resourceFail`), incremented once per finding regardless of control dedup.
- `score` is computed from these resource-level counters instead of `passControls.size / (passControls.size + failControls.size)`.

Footnote text (line 814, currently reads "Scoring is strict: a control counts as failed if even one resource fails it...") is reworded to describe the new resource-weighted methodology.

## What stays the same

- The existing control-level dedup (`passControls` / `failControls` Sets) is **kept** — it still feeds the "X failed controls" caption (line 1185) and the framework sort order (`sort by failedControls desc`). It no longer drives the score itself, but remains available as a stricter, separate signal for anyone who wants "how many distinct controls have zero tolerance for failure."
- 70% pass/fail badge threshold (`isPassing = fw.score >= 70`), severity breakdown (critical/high/medium/low counts), trend chart, findings table, and the existing total-findings dedup (96/672 counts) are untouched — none of that logic depended on the control-level all-or-nothing rule.
- No backend/API changes. `findings` already arrive pre-filtered to `PASS`/`FAIL` only (line 268), so no new status handling is needed.

## Scope

Single file (`ComplianceOpsPage.tsx`), one `useMemo` block plus one footnote string. No migration, no new entity fields, no controller changes.

## Known effect (flagged to stakeholder, not a defect)

Existing accounts will see framework scores jump immediately on deploy (e.g. CIS 8% → roughly matching resource pass rate, likely 80s-90s) with no underlying infrastructure change — this is the intended effect of the methodology switch, not a bug. Whether this needs a customer-facing note is a product decision outside this spec's scope.
