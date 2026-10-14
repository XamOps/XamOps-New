import { describe, it, expect, vi, beforeEach } from 'vitest';

const apiFetch = vi.hoisted(() => vi.fn());
vi.mock('../../utils/apiClient', () => ({ apiFetch }));

import {
  fetchChargeTypes,
  fetchChargesByService,
  fetchServiceResources,
} from '../curInsightsApi';

/**
 * What the cost-basis fetchers put on the wire.
 *
 * <p>The one behaviour worth pinning here is an omission. `CurInsightCache` keys on the raw path
 * and query string, so `basis=unblended` would fork a second server-side entry and a second Athena
 * scan for figures identical to the ones already cached — exactly what the react-query no-suffix
 * rule prevents one layer up. `CostBasis.from(null)` is `UNBLENDED`, so omitting it says the same
 * thing to the backend.
 */
const url = (call = 0): string => String(apiFetch.mock.calls[call][0]);

const ok = (body: unknown) =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });

describe('the cost basis on the wire', () => {
  beforeEach(() => {
    apiFetch.mockReset();
  });

  it('is omitted for unblended and sent for amortized', async () => {
    apiFetch.mockResolvedValue(ok([]));

    await fetchChargesByService('123456789012', '2026-08', 'unblended');
    expect(url()).toContain('period=2026-08');
    expect(url()).not.toContain('basis');

    await fetchChargesByService('123456789012', '2026-08', 'amortized');
    expect(url(1)).toContain('basis=amortized');
  });

  it('behaves the same way on the charge-type ledger', async () => {
    apiFetch.mockResolvedValue(ok([]));

    await fetchChargeTypes('123456789012', '2026-08');
    expect(url()).not.toContain('basis');

    await fetchChargeTypes('123456789012', '2026-08', 'amortized');
    expect(url(1)).toContain('basis=amortized');
  });

  it('encodes a service name rather than concatenating it into the query string', async () => {
    // A service name is free text from the report: "AWS Backup & Restore" concatenated raw would
    // cut the query string in half at the &, and "Route 53 Resolver + DNS Firewall" would lose the
    // + to a space.
    apiFetch.mockResolvedValue(ok({ resources: [], totalCost: 0, groupCount: 0 }));

    await fetchServiceResources('123456789012', 'AWS Backup & Restore', '2026-08');

    expect(url()).toContain('service=AWS+Backup+%26+Restore');
    expect(url()).not.toContain('basis');
  });
});
