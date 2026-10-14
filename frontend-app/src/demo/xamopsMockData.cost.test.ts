import { describe, it, expect } from 'vitest';
import {
  demoChargeTypes,
  demoChargesByService,
  demoServiceResources,
  demoTopResources,
} from './xamopsMockData';

/**
 * The demo cost fixtures' internal arithmetic.
 *
 * <p>Fixture-level rather than routing-level, which is why this is its own file beside
 * `warMockData.test.ts` and `complianceReportDemo.test.ts`. Four fixtures describe the same month
 * and the Cost page renders them next to each other, so a figure that disagrees with its neighbour
 * does not read as a demo-data problem — it reads as the product being wrong.
 */

const TOTAL = 18420.75;

/** To the cent, because these are sums of hand-written decimals. */
const cents = (value: number): number => Math.round(value * 100);

describe('demo cost fixtures', () => {
  it('has every resource row summing to the total declared beside them', () => {
    // This is the assertion that would have caught the two services with no resource rows at all:
    // the rows summed to 17,280.75 against a declared 18,420.75, and the per-service panel derives
    // from those rows.
    const rows = demoTopResources.resources.reduce((sum, r) => sum + r.cost, 0);
    expect(cents(rows)).toBe(cents(demoTopResources.totalCost));
    expect(cents(demoTopResources.totalCost)).toBe(cents(TOTAL));
  });

  it('has the invoice summing to the same total', () => {
    const invoice = demoChargesByService.reduce((sum, c) => sum + c.cost, 0);
    expect(cents(invoice)).toBe(cents(TOTAL));
  });

  it('has the charge-type ledger summing to the same total', () => {
    // So the ledger shows its "matches the total above" branch rather than the sub-cent-groups
    // explanation, which would be a correct behaviour demonstrated on incorrect data.
    const ledger = demoChargeTypes.reduce((sum, t) => sum + t.cost, 0);
    expect(cents(ledger)).toBe(cents(TOTAL));
  });

  it('has both a charge and a credit, so the ledger shows both of its groups', () => {
    expect(demoChargeTypes.some((t) => t.cost > 0)).toBe(true);
    expect(demoChargeTypes.filter((t) => t.cost < 0)).toHaveLength(2);
  });

  it('resolves every invoiced service to a panel totalling that service to the cent', () => {
    const invoiceByService = new Map<string, number>();
    for (const charge of demoChargesByService) {
      invoiceByService.set(charge.service, (invoiceByService.get(charge.service) ?? 0) + charge.cost);
    }

    for (const [service, invoiceTotal] of invoiceByService) {
      const panel = demoServiceResources(service);

      // Non-empty even for the two zero-total services, so the scoped path's empty state — which
      // asserts the service genuinely has no resource-level rows — is never shown for a service
      // the demo invoice visibly lists.
      expect(panel.resources.length, `${service} has no resource rows`).toBeGreaterThan(0);
      expect(panel.groupCount).toBe(panel.resources.length);
      expect(cents(panel.totalCost), `${service} panel total`).toBe(cents(invoiceTotal));
    }
  });

  it('returns an empty panel for a service the demo does not bill', () => {
    // A well-formed question with a legitimately empty answer, which is a 200 rather than a 404.
    const panel = demoServiceResources('AWS Snowball');
    expect(panel.resources).toEqual([]);
    expect(panel.totalCost).toBe(0);
    expect(panel.groupCount).toBe(0);
  });
});
