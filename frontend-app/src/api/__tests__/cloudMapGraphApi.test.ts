import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const apiFetch = vi.hoisted(() => vi.fn());
vi.mock('../../utils/apiClient', () => ({ apiFetch }));

import { fetchCloudMapGraph } from '../cloudMapGraphApi';

/**
 * How the topology fetcher classifies an answer — criterion 7a.
 *
 * <p>This is the only place the distinction can be tested. The Connectivity tab's own tests inject
 * their fetchers, so nothing there reaches this module, and the whole point of the classification
 * is that two successful HTTP responses mean different things: `[]` is the backend's own
 * collapsed-failure path (`CloudMapService` turns nearly every internal failure into a successful
 * empty list), so the tab has to reason about it as a value and renders "no topology came back"
 * with a reload. A body that is not an array is something answering in the service's place, which
 * is a failed read — state 1a, with a retry — and resolving it as "empty" would tell the reader
 * their account has no topology because a proxy returned an HTML error page.
 */

const ACCOUNT = '605134457560';

const ok = (body: unknown) =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });

const url = (call = 0): string => String(apiFetch.mock.calls[call][0]);

describe('the Cloud Map graph fetcher', () => {
  let warn: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    apiFetch.mockReset();
    warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  });

  afterEach(() => {
    warn.mockRestore();
  });

  it('asks for the whole estate in one request, and passes the caller\'s timeout', async () => {
    apiFetch.mockResolvedValue(ok([]));

    await fetchCloudMapGraph({ accountId: ACCOUNT, vpcId: 'ALL', region: 'ap-south-1', timeoutMs: 110_000 });

    expect(url()).toContain(`accountId=${ACCOUNT}`);
    // Omitting vpcId is the backend's "global view" branch, which filters to resources with no
    // VPC — an EC2 instance would not be in the payload at all.
    expect(url()).toContain('vpcId=ALL');
    expect(url()).toContain('region=ap-south-1');
    expect(url()).not.toContain('forceRefresh');
    expect(apiFetch.mock.calls[0][1]).toEqual({ timeoutMs: 110_000 });
  });

  it('resolves an empty array, because that is an answer and not a failure', async () => {
    apiFetch.mockResolvedValue(ok([]));

    await expect(fetchCloudMapGraph({ accountId: ACCOUNT, vpcId: 'ALL' })).resolves.toEqual([]);
    expect(warn).not.toHaveBeenCalled();
  });

  it('throws on a 200 whose body is not an array, and says so once', async () => {
    // The shape a proxy or an error-page interceptor produces: a successful status over a body
    // that is not the payload.
    apiFetch.mockResolvedValue(ok({ error: 'Internal Server Error' }));

    await expect(fetchCloudMapGraph({ accountId: ACCOUNT, vpcId: 'ALL' }))
      .rejects.toThrow('cloudmap/graph: unexpected shape');
    expect(warn).toHaveBeenCalledTimes(1);
    // The url is in the warning because the body is not, and without it the line cannot be tied
    // to an account or a region.
    expect(String(warn.mock.calls[0][1])).toContain('/api/xamops/cloudmap/graph');
  });

  it('throws on an unparseable body for the same reason', async () => {
    apiFetch.mockResolvedValue(new Response('<html>502 Bad Gateway</html>', { status: 200 }));

    await expect(fetchCloudMapGraph({ accountId: ACCOUNT, vpcId: 'ALL' }))
      .rejects.toThrow('cloudmap/graph: unexpected shape');
  });

  it('throws the Cloud Map page\'s own message on a non-2xx', async () => {
    apiFetch.mockResolvedValue(new Response('', { status: 502 }));

    // Pinned verbatim to useCloudMap.loadGraph's message, which that page renders. Nothing here
    // depends on the text; pinning it is what makes unifying the two fetchers later provably
    // behaviour-preserving rather than a rewrite of user-visible copy.
    await expect(fetchCloudMapGraph({ accountId: ACCOUNT, vpcId: 'ALL' }))
      .rejects.toThrow('HTTP error! status: 502');
    expect(warn).not.toHaveBeenCalled();
  });
});
