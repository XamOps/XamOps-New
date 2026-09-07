# Sample CDN consumption report — all geographies, rates, and discount

The detail behind the single **AWS CloudFront Consumption** line on the
[invoice](./cloudfront-invoice-sample.md). Every geography, the private rate applied, what
AWS charged for the same usage, and how the 12% CloudFront discount lands.

All pricing figures are computed by running `CloudFrontPricingService` over a month of
synthetic CUR rows — none are hand-calculated.

- **Client:** Northwind Media Ltd (client id `4021`)
- **AWS account:** `4025-8817-3390`
- **Billing period:** July 2026 (closed)
- **Source:** `CUR` — Cost and Usage Report 2.0
- **CUR rows folded in:** 17 (a real month is 10⁴–10⁵)
- **Discount:** `Amazon CloudFront` @ 12.00%
- **Invoice line 3:** $2,907.12 → ₹257,047.55 @ ₹88.42

---

## Summary

| | Value |
|---|--:|
| Data transferred to viewers | **45,819.62 GB** (44.75 TiB) |
| Requests served | **40,156,250** |
| Cache invalidation paths | **1,200** |
| Egress to origin (not viewer traffic) | 402.55 GB |
| Billing geographies seen | **6** |
| Gross at private rates | **$3,303.55** |
| CloudFront discount @ 12% | **−$396.43** |
| **Net billed** | **$2,907.12** |
| AWS cost for the same usage | $2,527.60 |
| **Retained margin after discount** | **$379.52** (13.1%) |
| Blended net rate, egress | $0.0620 / GB |

---

## Data transfer out — all geographies

| Geography | Volume | Share | Rate applied | Gross | Discount 12% | Net | AWS cost |
|---|--:|--:|---|--:|--:|--:|--:|
| United States | 25,615.59 GB | 55.9% | $0.0620 / GB | $1,588.17 | −$190.58 | **$1,397.59** | $1,152.51 |
| Europe | 10,576.89 GB | 23.1% | $0.0680 / GB | $719.23 | −$86.31 | **$632.92** | $475.93 |
| India | 6,119.18 GB | 13.4% | $0.0980 / GB | $599.68 | −$71.96 | **$527.72** | $520.53 |
| `APS1` ⚠ | 1,770.52 GB | 3.9% | *at AWS cost* | $149.33 | −$17.92 | **$131.41** | $149.33 |
| Asia Pacific | 1,143.77 GB | 2.5% | $0.0900 / GB | $102.94 | −$12.35 | **$90.59** | $96.48 |
| Middle East ⚠ | 593.67 GB | 1.3% | *at AWS cost* | $71.24 | −$8.55 | **$62.69** | $71.24 |
| **Subtotal** | **45,819.62 GB** | **100%** | $0.0705 blended | **$3,230.59** | **−$387.67** | **$2,842.92** | **$2,466.02** |

India is 13.4% of volume but 18.6% of gross egress, because it costs roughly 1.6× the US
rate to serve — the usual shape for a CDN with South Asian reach. US and Europe together are
79% of volume at the two cheapest rates.

**Two geographies go under water once the discount applies.** `APS1` and Middle East are
passed through at AWS cost with no private rate, so discounting them 12% bills below cost:

| Geography | Net billed | AWS cost | Margin |
|---|--:|--:|--:|
| `APS1` | $131.41 | $149.33 | **−$17.92** |
| Middle East | $62.69 | $71.24 | **−$8.55** |

India is also thin at $527.72 net against $520.53 of cost — $7.19, or 1.4%. A small drop in
the INR/USD rate or a shift in traffic mix turns it negative.

---

## Requests — all geographies

| Geography | Requests | Share | Rate applied | Gross | Discount 12% | Net | AWS cost |
|---|--:|--:|---|--:|--:|--:|--:|
| United States | 26,857,500 | 66.9% | $0.0120 / 10,000 | $32.23 | −$3.87 | **$28.36** | $23.15 |
| Europe | 7,685,000 | 19.1% | $0.0120 / 10,000 | $9.22 | −$1.11 | **$8.11** | $6.92 |
| India | 5,613,750 | 14.0% | $0.0120 / 10,000 | $6.74 | −$0.81 | **$5.93** | $6.74 |
| **Subtotal** | **40,156,250** | **100%** | | **$48.19** | **−$5.79** | **$42.40** | **$36.81** |

Average object size is **1.17 MB** (45,819.62 GB ÷ 40,156,250) — media delivery, not API
traffic.

US requests fold two usage types, `Requests-Tier1` (HTTP) and `Requests-Tier2` (HTTPS). Both
bill at one private rate so they collapse to a single line, while AWS prices them
differently — which is why the cost column is not a flat multiple of the count.

India requests bill $5.93 net against $6.74 of cost, a **−$0.81** loss. Small in absolute
terms, and worth noting only because request pricing is uniform across geographies while
AWS's own request cost is not.

---

## Other charges

| Item | Quantity | Rate applied | Gross | Discount 12% | Net | AWS cost |
|---|--:|---|--:|--:|--:|--:|
| Global — invalidations | 1,200 Paths | $0.0050 / path | $6.00 | −$0.72 | **$5.28** | $6.00 |
| US — origin egress ⚠ | 402.55 GB | *at AWS cost* | $18.77 | −$2.25 | **$16.52** | $18.77 |
| **Subtotal** | | | **$24.77** | **−$2.97** | **$21.80** | **$24.77** |

---

## Reconciliation to the invoice

| | Gross | Discount | Net |
|---|--:|--:|--:|
| Data transfer out | $3,230.59 | −$387.67 | $2,842.92 |
| Requests | $48.19 | −$5.79 | $42.40 |
| Other charges | $24.77 | −$2.97 | $21.80 |
| **Total** | **$3,303.55** | **−$396.43** | **$2,907.12** |

$2,907.12 × ₹88.42 = **₹257,047.55** — invoice line 3.

Discount is shown allocated per geography for review, but it is computed once against the
CloudFront total: `$3,303.55 × 12% = $396.43`. Per-line figures are rounded individually and
sum to the same value.

### CUR vs Cost Explorer

| | Amount |
|---|--:|
| CloudFront per CUR (what AWS billed) | $2,527.60 |
| CloudFront per Cost Explorer | $2,511.84 |
| Difference | $15.76 (0.6%) |

Cost Explorer under-reports because it redistributes some data-transfer charges and omits
others. CUR is the source AWS generates the invoice from, which is why closed months are
served from it. The current month still comes from Cost Explorer — CUR is not final until
roughly 72 hours after a period ends.

```
GET /api/billops/cur/reconcile?accountId=402588173390&year=2026&month=7
```

---

## ⚠ Three items did not price against the rate card

Each is the pricing step declining to guess rather than a failure, but all three should be
resolved before the invoice is sent — and the discount makes two of them loss-making.

**1. `APS1` — 1,770.52 GB, $149.33 at cost, −$17.92 after discount**

`APS1` is the **ap-south-1 region code**, not a viewer geography. CloudFront geographies are
always two letters, because AWS substitutes a two-letter abbreviation for the region in
CloudFront usage types; a prefix carrying digits denotes a region-scoped charge such as
Origin Shield.

Left unpriced deliberately. `ap-south-1` is physically Mumbai, so folding it into India
looks reasonable — but Origin Shield is a different charge from viewer egress, and pricing it
per-GB at India's viewer rate would be a guess that happens to look plausible. Decide
explicitly: add a rate, or exclude it from the discount base.

**2. Middle East — 593.67 GB, $71.24 at cost, −$8.55 after discount**

Not on the rate card at all. 1.3% of volume, so probably a geography that became relevant
after the card was written. Add a rate above cost.

**3. `US` / `OTHER` — 402.55 GB, $18.77 at cost, −$2.25 after discount**

`US-DataTransfer-Out-OBytes` is egress **to the origin**, not viewer traffic, and is
correctly excluded from per-GB viewer pricing. No action needed unless origin egress is meant
to be billed separately — though it is currently being discounted, which is worth a look.

---

## Rate card in effect

| Region matcher | Usage type matcher | Rate | Unit |
|---|---|--:|---|
| `United States` | `Bandwidth` | 0.0620 | Per GB |
| `Europe` | `Bandwidth` | 0.0680 | Per GB |
| `Asia Pacific (Mumbai)` | `Bandwidth` | 0.0980 | Per GB |
| `AP` | `Bandwidth` | 0.0900 | Per GB |
| `all` | `Requests` | 0.0120 | Per 10000 Requests |
| `all` | `Invalidations` | 0.0050 | Per Path |

Mixed vocabulary on purpose — display names, a legacy AWS region name, a bare geography
code, and wildcards, which is what a real rate card accumulates. India was priced through
`Asia Pacific (Mumbai)`, AWS invoice wording from the older PDF flow, matched against a CUR
geography of `IN`.

---

## Notes on reading this report

**Volume drives the bill, not AWS cost.** Gross is `quantity × private rate`. AWS cost sits
alongside as a margin reference and is never billed directly. A percentage off AWS list
would be a different product.

**Geography is decoded from the usage-type prefix.** CloudFront is global, so CUR leaves the
region column blank on its line items; `IN-DataTransfer-Out-Bytes` means Indian edge
locations. Grouping by CUR's region column would put nearly everything under "Global".

**Rates quoted per block are divided, not multiplied.** 26,857,500 requests at
`$0.0120 per 10,000` is `26,857,500 ÷ 10,000 × 0.0120 = $32.23`. Multiplying the raw count
would have billed $322,290.

**Rows are folded, not sampled.** A month of daily rows per geography and usage class
collapses to one line per rate. `CUR rows folded in` is the diagnostic — a sudden drop
signals a partial export rather than a quiet month.
