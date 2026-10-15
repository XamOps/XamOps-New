import { describe, it, expect } from 'vitest';
import {
  demoChargeTypes,
  demoChargesByService,
  demoCloudMapGraph,
  demoResourceCosts,
  demoResourceIdentities,
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

/**
 * The connectivity fixtures against the graph fixture they have to line up with.
 *
 * <p>Written independently, the two shared no identifier at all — so every demo resource landed in
 * "not in the account topology" and a working feature looked broken. These are what keep the
 * reconciliation from drifting back.
 */
describe('demo connectivity fixtures', () => {
  const nodeIds = new Set(
    (demoCloudMapGraph as { group: string; data: { id: string } }[])
      .filter((element) => element.group === 'nodes')
      .map((element) => element.data.id),
  );
  const edges = (demoCloudMapGraph as { group: string; data: { source?: string; target?: string } }[])
    .filter((element) => element.group === 'edges');
  const curIds = demoTopResources.resources.map((resource) => resource.resourceId).filter(Boolean);

  it('has no edge pointing at a node that does not exist', () => {
    // The regression the id renaming risks, and the Cloud Map demo page reads this same fixture —
    // a dangling edge there would trade a fix here for a break there.
    for (const edge of edges) {
      expect(nodeIds.has(edge.data.source ?? ''), `source ${edge.data.source}`).toBe(true);
      expect(nodeIds.has(edge.data.target ?? ''), `target ${edge.data.target}`).toBe(true);
    }
  });

  it('names a real graph node in every identity it claims to resolve', () => {
    const resolved = demoResourceIdentities(curIds.join(','))
      .identities.filter((entry) => entry.resourceId !== null);

    expect(resolved.length).toBeGreaterThan(0);
    for (const entry of resolved) {
      // At least one candidate has to hit, which is how the client roots the flow: the short id
      // on an inventory-plane graph, the ARN on a describe-scan one.
      expect(
        entry.nodeIdCandidates.some((candidate) => nodeIds.has(candidate)),
        `${entry.requestedId} resolves to nothing in the graph`,
      ).toBe(true);
    }
  });

  it('derives every cost from a demoTopResources row, so the demo cannot drift from the total', () => {
    const costs = demoResourceCosts(curIds.join(',')).costs;

    expect(costs.length).toBeGreaterThan(0);
    for (const entry of costs) {
      const rows = demoTopResources.resources.filter((r) => r.resourceId === entry.resourceId);
      expect(rows.length, `${entry.resourceId} is not a demoTopResources row`).toBeGreaterThan(0);
      expect(cents(entry.cost)).toBe(cents(rows.reduce((sum, r) => sum + r.cost, 0)));
    }
  });

  it('refuses the short id two live resources answer to, and prices each of them by ARN', () => {
    // `db:xammer-prod` and `cluster/xammer-prod` both tail to `xammer-prod`, which is the
    // collision production refuses to guess at. The demo reproduces it rather than papering over
    // it: a demo that quietly worked where production does not would be the worse of the two.
    expect(demoResourceCosts('xammer-prod').ambiguous).toEqual(['xammer-prod']);
    expect(demoResourceCosts('xammer-prod').costs).toEqual([]);
    expect(demoResourceIdentities('xammer-prod').identities[0].resourceId).toBeNull();

    const rds = demoResourceCosts('arn:aws:rds:ap-south-1:605134400000:db:xammer-prod');
    const eks = demoResourceCosts('arn:aws:eks:ap-south-1:605134400000:cluster/xammer-prod');
    expect(cents(rds.costs[0].cost)).toBe(cents(2540.40));
    expect(cents(eks.costs[0].cost)).toBe(cents(580.00));
    expect([...rds.ambiguous, ...eks.ambiguous]).toEqual([]);
  });

  it('prices an AWS-generated id two rows claim, because the two are one machine', () => {
    // The other half of the pair above, and the one measured on a real account: the instance and
    // its SSM facet are two claimants of `i-0abc1001a2b3c4d5e`, and AWS minted that id, so it is
    // one machine seen twice rather than two resources. Refusing it blanked 73 of 80 instances on
    // account 605134457560 — the shape that cannot be told apart from `xammer-prod` by ARN, only
    // by the id.
    const instance = demoResourceCosts('i-0abc1001a2b3c4d5e');

    expect(instance.ambiguous).toEqual([]);
    expect(cents(instance.costs[0].cost)).toBe(cents(1621.35));
    expect(demoResourceIdentities('i-0abc1001a2b3c4d5e').identities[0].resourceId)
      .toBe('i-0abc1001a2b3c4d5e');
  });

  it('leaves the two load balancers unresolvable and unpriced', () => {
    // The demo's Group C case: a load balancer is exactly the type the billing-to-inventory join
    // cannot reach on a describe-scan account, so it renders the real "no cost attributable"
    // sentence. Asserted so the gap cannot be "fixed" by accident later.
    for (const alb of ['alb-prod-web-001', 'alb-prod-api-001']) {
      expect(nodeIds.has(alb)).toBe(true);
      expect(demoResourceIdentities(alb).identities[0].resourceId).toBeNull();
      expect(demoResourceCosts(alb).costs).toEqual([]);
      expect(demoResourceCosts(alb).ambiguous).toEqual([]);
    }
  });
});
