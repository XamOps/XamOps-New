# Sample invoice — combined AWS bill with CloudFront as its own discounted line

The customer-facing GST tax invoice. It carries **three line items**, not a CloudFront
breakdown: overall AWS consumption with its discount, Marketplace undiscounted, and
CloudFront as a single total with its own discount. Per-geography detail lives in the
[CDN consumption report](./cloudfront-consumption-report-sample.md).

Structure and arithmetic follow `InvoiceManagementService` — the `STANDARD` / `CLOUDFRONT`
grouping, per-service discount scopes in `recalculateTotals`, undiscounted Marketplace,
IGST, and round-off.

> The CloudFront discount on line 3 used to be dropped from the rendered PDF while still
> being present on the saved invoice. That is [now fixed](#fixed-cloudfront-discount-was-lost-in-the-pdf);
> the figures below are what both the record and the PDF produce.

- **Client:** Northwind Media Ltd (client id `4021`)
- **AWS account:** `4025-8817-3390`
- **Billing period:** July 2026 (closed)
- **Invoice no:** `A7F3C1E29B04` · **Date:** 2026-08-03
- **Source:** `CUR` — Cost and Usage Report 2.0
- **FX rate:** ₹88.42 / USD · **Place of supply:** inter-state → IGST 18%

---

## Tax invoice

| Sl | Description | HSN/SAC | Amount (₹) |
|--:|---|:--:|--:|
| 1 | **AWS Consumption Charge**<br>*Amazon Web Service Charges*<br>*For the Period of July 2026*<br>*Account ID: 4025-8817-3390*<br>*($8,097.08 @88.42)*<br>*Discount: 8.00% (-$704.09)* | 998315 | 715,943.81 |
| 2 | **AWS Consumption Charge - Marketplace**<br>*Amazon Web Services, Inc. - Marketplace*<br>*For the Period of July 2026*<br>*Account ID: 4025-8817-3390*<br>*($850.00 @88.42)* | 998315 | 75,157.00 |
| 3 | **AWS CloudFront Consumption**<br>*Amazon CloudFront Charges*<br>*For the Period of July 2026*<br>*($2,907.12 @88.42)*<br>*Discount: 12.00% (-$396.43)* | 998315 | 257,047.55 |
| | | **Taxable value** | **1,048,148.36** |
| | IGST @ 18% | 18 % | 188,666.70 |
| | Round Off | | 0.06 |
| | **Total** | | **₹ 1,236,815** |

Bill Details: New Ref `A7F3C1E29B04` ₹1,236,815 *Dr*

Intra-state supply would split the same 18% into CGST @ 9% (₹94,333.35) and
SGST @ 9% (₹94,333.35).

---

## How each line was derived

Working currency is USD; each line converts to INR independently, so the taxable value is
the sum of rounded line amounts rather than a rounded sum.

### Line 1 — AWS Consumption Charge (discountable)

Every non-CloudFront service, less Marketplace:

| Service | AWS cost |
|---|--:|
| Amazon EC2 | $4,182.55 |
| Amazon RDS | $1,864.20 |
| Amazon S3 | $1,109.87 |
| Amazon ElastiCache | $486.90 |
| Amazon CloudWatch | $168.72 |
| AWS Lambda | $312.44 |
| Data Transfer | $274.31 |
| Other services | $402.18 |
| **Discountable subtotal** | **$8,801.17** |

`AWS Consumption Charge` discount @ 8% → −$704.09 → **$8,097.08** → ₹715,943.81

The 8% base deliberately excludes both CloudFront and Marketplace. CloudFront is discounted
separately at its own rate; Marketplace is never discounted.

### Line 2 — Marketplace (never discounted)

$850.00 of third-party AWS Marketplace charges. Part of the consumption total, carried on
its own line and excluded from every discount base, because these are pass-through charges
from third-party sellers with no margin to concede.

$850.00 → ₹75,157.00

### Line 3 — CloudFront (private rates, then its own discount)

One total, priced at the client's private per-GB and per-request rates rather than as a
percentage off AWS cost:

| | Amount |
|---|--:|
| CloudFront at private rates | $3,303.55 |
| `Amazon CloudFront` discount @ 12% | −$396.43 |
| **Net** | **$2,907.12** → ₹257,047.55 |

Full geography breakdown behind the $3,303.55 is in the
[consumption report](./cloudfront-consumption-report-sample.md). AWS's own cost for the
same usage was $2,527.60, so after the discount the retained margin is $379.52 (13.1%).

---

## AWS cost vs invoiced

| | Amount |
|---|--:|
| Actual AWS bill (CUR unblended, all services) | $12,178.77 |
| — of which CloudFront | $2,527.60 |
| Invoice pre-discount | $12,954.72 |
| Total discounts | −$1,100.52 |
| Invoice taxable value | $11,854.20 |

Invoice pre-discount exceeds the AWS bill because CloudFront is uplifted to private rates
($3,303.55 against $2,527.60 of cost) while everything else passes through at cost and is
then discounted. Net across the whole invoice is $11,854.20 billed against $12,178.77 of
AWS cost — the CloudFront margin does not fully offset an 8% discount on $8.8k of standard
consumption. That is a commercial outcome, not an error, but it is the number to look at
when the discount rate is next negotiated.

---

## Fixed: CloudFront discount was lost in the PDF

Line 3 shows the discount the invoice **record** carries. The PDF renderer did not apply it.

`recalculateTotals` handles three discount scopes correctly:

| Scope | Base |
|---|---|
| `ALL` | pre-discount total − Marketplace |
| `AWS Consumption Charge` | non-CloudFront line items − Marketplace |
| any service name, e.g. `Amazon CloudFront` | that service's line items |

So `discountAmount` and `amount` on the saved invoice are right. The PDF then diverges:

- The **STANDARD** row subtracts a discount. When a consumption-level discount exists it
  recomputes `stdTotal × pct` and sets `showDiscountInDesc = true`.
- That flag gates the fallback `discountAmount = dto.getDiscountAmount()`, which is the only
  path carrying per-service discounts into the PDF.
- The **CLOUDFRONT** row sums `getCost()` and converts to INR. It never looks at discounts.

With both discounts present — exactly this invoice — the CloudFront discount is applied by
neither row:

| | Amount |
|---|--:|
| PDF taxable value as rendered today | ₹1,083,200.70 |
| Invoice record taxable value | ₹1,048,148.36 |
| **Over-stated on the PDF** | **₹35,052.34** ($396.43 × 88.42) |

The customer was invoiced roughly ₹41,362 more than the record said once IGST compounded it.
With a CloudFront-only discount and no consumption discount the total came out right but
landed on the wrong line: deducted from AWS Consumption, with CloudFront shown at full price.

An `ALL`-scoped discount had the same shape of bug in the other direction. Its base spans
everything but Marketplace, CloudFront included, yet it was only ever applied to the
consumption row — under-discounting the invoice by CloudFront's share.

One caveat on splitting a shared base: each row's share is rounded independently, because
each row converts to INR on its own and has to be a settled 2 dp figure. For an `ALL`
discount the sum can therefore land up to one paisa from the single-base figure
(₹1,210.48 against ₹1,210.47 on this invoice). Unavoidable once a base spans rows, and
bounded at 0.01 per row.

### The fix

Both discounts are now attributed once, up front, by `attributeDiscounts`, which mirrors the
scope rules in `recalculateTotals` so the rendered invoice and the stored one cannot drift:

| Scope | Consumption row | CloudFront row |
|---|---|---|
| `ALL` | `stdTotal × pct` | `cfTotal × pct` |
| `AWS Consumption Charge` (and GCP/Azure equivalents) | `stdTotal × pct` | — |
| `Amazon CloudFront` | — | `cfTotal × pct` |
| any other service name | that service's items `× pct` | — |

The CloudFront row now subtracts its share and prints its own `Discount: 12.00% (-$396.43)`
line. The consumption row no longer falls back to the invoice-wide `getDiscountAmount()`.

Covered by `InvoiceDiscountAttributionTest` (10 cases), including the invariant that the two
rows together deduct exactly what `recalculateTotals` deducts.

### Also fixed: GCP and Azure consumption scopes

`recalculateTotals` special-cased only `ALL` and `AWS Consumption Charge`. A discount scoped
`Google Cloud Consumption` or `Microsoft Azure Consumption` fell through to the per-service
branch, which matches line items by exact service name — and GCP/Azure line items are named
after individual services, so it matched nothing and discounted **zero** while the PDF showed
the concession. The reverse of the CloudFront bug: under-billing rather than over-billing.

Both scopes now resolve through one `isConsumptionScope` helper shared by
`recalculateTotals`, `recalculateTotalsForDto`, and `attributeDiscounts`, so a scope cannot be
recognised by one path and missed by another.

**This changes stored invoice amounts** for GCP and Azure invoices carrying such a discount —
downward, to match what those customers were already shown. Finalized invoices are not
recomputed retroactively; `recalculateTotals` runs on generation, discount changes, and
finalization.

---

## How line 3 reaches the invoice

`CloudFrontPricingService` is now wired into invoice generation. The generic line-item
builder copies whatever cost the detailed report carries, which for CloudFront is AWS cost —
wrong for a resold service. So after that loop runs,
`repriceCloudFrontAtPrivateRates` discards the cost-based CloudFront rows and rebuilds them:

1. Usage via `CloudFrontUsageService.getUsageFromAws` — reconciled CUR for closed months,
   Cost Explorer as fallback.
2. Priced by `CloudFrontPricingService` against the client's rate card.
3. One line item per geography and usage class, with `resourceName` carrying the working —
   `India — Data transfer out: 6,119.18 GB @ $0.098 / Per GB`.

The PDF then sums them into the single **AWS CloudFront Consumption** row. Unpriced
geographies are logged at WARN with their reasons, because an invoice carrying traffic from a
geography with no configured rate is incomplete and should be caught in review.

Any miss — non-AWS account, no client, no usage, nothing priced, or an exception — leaves the
cost-based lines untouched. Billing CloudFront at zero because a lookup failed would be worse
than billing it at cost.

## Reproducing

Line 3's figures come from `CloudFrontPricingService` over synthetic CUR rows — see the
consumption report. Lines 1 and 2 and all tax arithmetic are worked by hand against
`InvoiceManagementService`, and cross-checked in `Decimal`.
