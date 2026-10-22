import { describe, it, expect, vi, beforeEach } from 'vitest';
import { activitySummaryUrl, activityUrl, fetchActivity, fetchActivitySummary } from '../automationActivityApi';
import { adaptActivityEntry, adaptActivityPage, adaptActivitySummary } from '../automationActivityAdapter';
import {
  APPLIED,
  HPA_CHANGE,
  LEGACY,
  MODE,
  PUT_BACK,
  TEST_RUN,
  feedPage,
} from '../../components/EksDetails/sections/automation/activity/__tests__/activityFixtures';
import {
  activityResultChip,
  containerChangeText,
  proofChip,
  resultDetail,
} from '../../components/EksDetails/sections/automation/activity/activityText';
import type { ActivityEntryWire, ActivitySummaryWire } from '../../types/automationActivity';

vi.mock('../../utils/apiClient', () => ({
  default: { fetch: vi.fn() },
}));

import apiClient from '../../utils/apiClient';

const mockedFetch = apiClient.fetch as unknown as ReturnType<typeof vi.fn>;

const answer = (status: number, body: unknown): Response =>
  ({
    status,
    ok: status >= 200 && status < 300,
    text: async () => (body === null ? '' : JSON.stringify(body)),
  }) as unknown as Response;

describe('activityUrl', () => {
  it('sends the filters as the server names them, and the default page size', () => {
    expect(activityUrl('xamops-test-1', '102227938259', 'ap-south-1')).toBe(
      '/api/eks/automation/activity/xamops-test-1?accountId=102227938259&region=ap-south-1&limit=50',
    );
    const url = new URL(
      activityUrl('xamops-test-1', '102227938259', 'ap-south-1', {
        features: ['VERTICAL', 'HPA'],
        results: ['PUT_BACK'],
        q: '  idle batch ',
        limit: 20,
        cursor: 'MjAyNi0xMC0xNFQxMzoxMToyM1p8NDE',
      }),
      'http://test',
    );
    expect(url.searchParams.get('feature')).toBe('VERTICAL,HPA');
    expect(url.searchParams.get('result')).toBe('PUT_BACK');
    expect(url.searchParams.get('q')).toBe('idle batch');
    expect(url.searchParams.get('limit')).toBe('20');
    expect(url.searchParams.get('cursor')).toBe('MjAyNi0xMC0xNFQxMzoxMToyM1p8NDE');
  });

  it('caps the search at the server limit of 100 characters', () => {
    const url = new URL(activityUrl('c', '1', 'r', { q: 'x'.repeat(150) }), 'http://test');
    expect(url.searchParams.get('q')).toHaveLength(100);
  });

  it('sends a time window as from and to (FEAT-006: the Overview week, the Activity time filter)', () => {
    const url = new URL(activityUrl('c', '1', 'r', { from: '2026-10-07T10:00:00.000Z', to: '2026-10-14T10:00:00.000Z', limit: 200 }), 'http://test');
    expect(url.searchParams.get('from')).toBe('2026-10-07T10:00:00.000Z');
    expect(url.searchParams.get('to')).toBe('2026-10-14T10:00:00.000Z');
    expect(url.searchParams.get('limit')).toBe('200');
    expect(new URL(activityUrl('c', '1', 'r', { from: null }), 'http://test').searchParams.has('from')).toBe(false);
  });
});

describe('the per-day summary (FEAT-005 → FEAT-006)', () => {
  beforeEach(() => mockedFetch.mockReset());

  const SUMMARY: ActivitySummaryWire = {
    engine: { enabled: true }, timezone: 'Asia/Kolkata', from: '2026-10-08', to: '2026-10-14',
    firstRecordedAt: '2026-10-11T16:30:12.123456Z', completeSince: '2026-10-13T04:00:00Z', truncated: false,
    days: [
      { date: '2026-10-14', applied: 2, putBack: 1, refused: 0, testRuns: 3, failed: 0, byFeature: { VERTICAL: { applied: 1, putBack: 1, refused: 0, testRuns: 3, failed: 0 }, HPA: { applied: 1, putBack: 0, refused: 0, testRuns: 0, failed: 0 } } },
      { date: '2026-10-13', applied: 0, putBack: 0, refused: 1, testRuns: 0, failed: 0, byFeature: {} },
      { date: 'not a day', applied: 9, putBack: 9, refused: 9, testRuns: 9, failed: 9 },
    ],
  };

  it('asks for the window and the features as the server names them, within its 1 to 30 days', () => {
    expect(activitySummaryUrl('xamops-test-1', '102227938259', 'ap-south-1', 7)).toBe(
      '/api/eks/automation/activity/xamops-test-1/summary?accountId=102227938259&region=ap-south-1&days=7',
    );
    expect(new URL(activitySummaryUrl('c', '1', 'r', 90, ['HPA']), 'http://test').searchParams.get('days')).toBe('30');
    expect(new URL(activitySummaryUrl('c', '1', 'r', 0, ['VERTICAL', 'HPA']), 'http://test').searchParams.get('feature')).toBe('VERTICAL,HPA');
  });

  it('keeps only the days the server listed, oldest first, and never fills a day it left out', () => {
    const view = adaptActivitySummary(SUMMARY);
    expect(view.days.map((d) => d.date)).toEqual(['2026-10-13', '2026-10-14']);
    expect(view.days[1]).toMatchObject({ applied: 2, putBack: 1, refused: 0, testRuns: 3, failed: 0 });
    expect(view.days[1].byFeature).toEqual({
      workload: { applied: 1, putBack: 1, refused: 0, testRuns: 3, failed: 0 },
      hpa: { applied: 1, putBack: 0, refused: 0, testRuns: 0, failed: 0 },
    });
    expect(view).toMatchObject({ from: '2026-10-08', to: '2026-10-14', timezone: 'Asia/Kolkata', engineEnabled: true, truncated: false });
    expect(view.firstRecordedAt).toBe(Date.parse('2026-10-11T16:30:12.123Z'));
    expect(view.completeSince).toBe(Date.parse('2026-10-13T04:00:00Z'));
    // Nothing recorded yet: no day, and the instants stay null.
    expect(adaptActivitySummary({ days: [] })).toMatchObject({ days: [], firstRecordedAt: null, completeSince: null, from: null });
  });

  it('reads the summary with one GET, and maps a backend without it, a refusal and a fault', async () => {
    const signal = new AbortController().signal;
    mockedFetch.mockResolvedValueOnce(answer(200, SUMMARY));
    const ok = await fetchActivitySummary('c', '1', 'r', 7, [], signal);
    expect(mockedFetch.mock.calls[0][1]).toEqual({ method: 'GET', signal });
    expect(ok.kind === 'ok' && ok.view.days).toHaveLength(2);
    mockedFetch.mockResolvedValueOnce(answer(404, null));
    expect(await fetchActivitySummary('c', '1', 'r', 7)).toEqual({ kind: 'not-deployed' });
    mockedFetch.mockResolvedValueOnce(answer(400, { code: 'K8S_INVALID_REQUEST', message: 'days must be 1 to 30.', field: 'days' }));
    expect(await fetchActivitySummary('c', '1', 'r', 7)).toMatchObject({ kind: 'refused', message: 'days must be 1 to 30.', field: 'days' });
    mockedFetch.mockResolvedValueOnce(answer(500, { code: 'K8S_INTERNAL', message: "XamOps couldn't read its activity log just now." }));
    expect(await fetchActivitySummary('c', '1', 'r', 7)).toEqual({ kind: 'error', message: "XamOps couldn't read its activity log just now." });
    mockedFetch.mockResolvedValueOnce(answer(200, { engine: { enabled: true } }));
    expect(await fetchActivitySummary('c', '1', 'r', 7)).toEqual({ kind: 'error', message: "The activity summary answered in a shape XamOps can't read." });
  });
});

describe('fetchActivity', () => {
  beforeEach(() => mockedFetch.mockReset());

  it('reads one page with a GET and adapts it', async () => {
    mockedFetch.mockResolvedValue(answer(200, feedPage([APPLIED], 'next')));
    const result = await fetchActivity('xamops-test-1', '102227938259', 'ap-south-1');
    expect(mockedFetch).toHaveBeenCalledTimes(1);
    expect(mockedFetch.mock.calls[0][1]).toEqual({ method: 'GET' });
    expect(result.kind).toBe('ok');
    if (result.kind !== 'ok') return;
    expect(result.page.engineEnabled).toBe(true);
    expect(result.page.nextCursor).toBe('next');
    expect(result.page.entries.map((e) => e.id)).toEqual(['40']);
  });

  it('tells an untyped 404 (no feed in this backend) from a typed one (cluster not registered)', async () => {
    mockedFetch.mockResolvedValueOnce(answer(404, { timestamp: '2026-10-15T10:00:00Z', status: 404, error: 'Not Found', path: '/api/eks/automation/activity/c' }));
    expect(await fetchActivity('c', '1', 'r')).toEqual({ kind: 'not-deployed' });
    mockedFetch.mockResolvedValueOnce(answer(404, null));
    expect(await fetchActivity('c', '1', 'r')).toEqual({ kind: 'not-deployed' });
    mockedFetch.mockResolvedValueOnce(answer(404, { code: 'K8S_CLUSTER_NOT_REGISTERED', message: "This cluster isn't registered with XamOps yet." }));
    expect(await fetchActivity('c', '1', 'r')).toEqual({ kind: 'cluster-not-registered', message: "This cluster isn't registered with XamOps yet." });
  });

  it('reads a 401 as a lapsed session, a typed 403 as a refusal and anything else as an error', async () => {
    mockedFetch.mockResolvedValueOnce(answer(401, null));
    expect(await fetchActivity('c', '1', 'r')).toEqual({ kind: 'unauthenticated' });
    mockedFetch.mockResolvedValueOnce(answer(403, { code: 'K8S_NO_ACCOUNT_ACCESS', message: "You don't have access to this account." }));
    const refused = await fetchActivity('c', '1', 'r');
    expect(refused.kind).toBe('refused');
    mockedFetch.mockResolvedValueOnce(answer(400, { code: 'K8S_INVALID_REQUEST', message: 'The cursor parameter is missing or malformed.' }));
    expect(await fetchActivity('c', '1', 'r')).toEqual({ kind: 'error', message: 'The cursor parameter is missing or malformed.' });
    mockedFetch.mockResolvedValueOnce(answer(500, null));
    expect(await fetchActivity('c', '1', 'r')).toEqual({ kind: 'error', message: 'The activity feed returned HTTP 500.' });
    mockedFetch.mockResolvedValueOnce(answer(200, { engine: { enabled: true } }));
    expect(await fetchActivity('c', '1', 'r')).toEqual({ kind: 'error', message: 'The activity feed answered without entries.' });
    mockedFetch.mockRejectedValueOnce(new Error('Network down'));
    expect(await fetchActivity('c', '1', 'r')).toEqual({ kind: 'error', message: 'Network down' });
  });
});

describe('adaptActivityEntry: both after_json shapes, nulls stay null', () => {
  it('reads a newer row: observed before and after, the ask, and the per-pod proof', () => {
    const e = adaptActivityEntry(APPLIED, 0);
    expect(e).toMatchObject({ id: '40', feature: 'workload', origin: 'MANUAL', who: 'A user', action: 'resize', result: 'applied', terminal: 'APPLIED', restartFree: true, beforeNotRecorded: false });
    expect(e.containers).toEqual([{
      container: 'api',
      before: { cpuRequestM: 1000, cpuLimitM: 1000, memRequestMi: 1024, memLimitMi: 1024 },
      after: { cpuRequestM: 25, cpuLimitM: 25, memRequestMi: 250, memLimitMi: 250 },
      requested: { cpuRequestM: 25, cpuLimitM: 25, memRequestMi: 250, memLimitMi: 250 },
    }]);
    expect(e.proof?.pods).toHaveLength(2);
    expect(e.at).toBe(Date.parse(APPLIED.at!));
  });

  it('reads a row from before the activity log: its "before" is the target it asked for, so it is not a before', () => {
    const e = adaptActivityEntry(LEGACY, 0);
    expect(e.beforeNotRecorded).toBe(true);
    expect(e.containers[0].before).toBeNull();
    expect(e.containers[0].requested).toEqual({ cpuRequestM: 25, cpuLimitM: 50, memRequestMi: null, memLimitMi: null });
    expect(e.proof).toBeNull();
    expect(e.origin).toBe('AUTO');
    expect(containerChangeText(e.containers[0], e).map((l) => `${l.field} ${l.text}`)).toEqual([
      'CPU request before not recorded → 25m',
      'CPU limit before not recorded → 50m',
    ]);
  });

  it('keeps every field the row does not hold null, never 0', () => {
    const e = adaptActivityEntry({ id: 7, at: null, outcome: 'APPLIED', result: 'APPLIED', change: { containers: [{ container: 'a', before: { cpuRequestMillis: null }, beforeBasis: 'OBSERVED', after: null, requested: null }] } }, 0);
    expect(e.at).toBeNull();
    expect(e.feature).toBeNull();
    expect(e.who).toBeNull();
    expect(e.containers[0]).toEqual({ container: 'a', before: null, after: null, requested: null });
    expect(e.hpa).toBeNull();
    expect(e.mode).toBeNull();
    expect(e.proof).toBeNull();
    expect(adaptActivityEntry({}, 3).id).toBe('row-3');
  });

  it('reads memory in Mi, to one decimal when it is not whole', () => {
    const e = adaptActivityEntry({ ...APPLIED, change: { containers: [{ container: 'a', before: { memRequestBytes: 100_000_000 }, beforeBasis: 'OBSERVED', after: null, requested: { memRequestBytes: 262_144_000 } }] } }, 0);
    expect(e.containers[0].before?.memRequestMi).toBe(95.4);
    expect(e.containers[0].requested?.memRequestMi).toBe(250);
  });

  it('reads HPA bounds and a mode change', () => {
    expect(adaptActivityEntry(HPA_CHANGE, 0).hpa).toEqual({
      before: { minReplicas: 1, maxReplicas: 5, targetUtilizationPct: 50 },
      after: { minReplicas: 1, maxReplicas: 8, targetUtilizationPct: 50 },
    });
    expect(adaptActivityEntry(MODE, 0).mode).toEqual({ from: 'RECOMMEND', to: 'AUTO' });
  });

  it('skips entries that are not objects', () => {
    const page = adaptActivityPage({ entries: [APPLIED, null as unknown as ActivityEntryWire, 'x' as unknown as ActivityEntryWire] });
    expect(page.entries).toHaveLength(1);
    expect(page.engineEnabled).toBeNull();
    expect(page.nextCursor).toBeNull();
  });
});

describe('the feed words: every chip has an icon and a label', () => {
  const entries = [PUT_BACK, APPLIED, TEST_RUN, HPA_CHANGE, MODE, LEGACY].map((w, i) => adaptActivityEntry(w, i));

  it('maps each result to its tone, icon and words', () => {
    expect(entries.map((e) => activityResultChip(e))).toEqual([
      { tone: 'warn', icon: 'mdi:undo-variant', label: 'Deferred, put back' },
      { tone: 'good', icon: 'mdi:check-circle', label: 'Applied' },
      { tone: 'info', icon: 'mdi:flask-outline', label: 'Test run' },
      { tone: 'good', icon: 'mdi:check-circle', label: 'Applied' },
      { tone: 'info', icon: 'mdi:tune-variant', label: 'Mode changed' },
      { tone: 'good', icon: 'mdi:check-circle', label: 'Applied' },
    ]);
  });

  it('says when a put-back was not confirmed, and never calls an unknown outcome a success', () => {
    const unconfirmed = adaptActivityEntry({ ...PUT_BACK, proof: { ...PUT_BACK.proof!, reverted: false } }, 0);
    expect(activityResultChip(unconfirmed)).toEqual({ tone: 'warn', icon: 'mdi:progress-question', label: 'Deferred, put back not confirmed' });
    expect(activityResultChip(adaptActivityEntry({ ...APPLIED, result: null, outcome: 'SOMETHING_NEW' }, 0))).toEqual({ tone: 'off', icon: 'mdi:help-circle-outline', label: 'Other: SOMETHING_NEW' });
    expect(activityResultChip(adaptActivityEntry({ ...HPA_CHANGE, result: null, outcome: 'QUEUED' }, 0)).label).toBe('Queued for the in-cluster agent');
    expect(activityResultChip(adaptActivityEntry({ ...APPLIED, result: 'FAILED', outcome: 'EXPIRED' }, 0)).label).toBe('Expired: nothing was sent');
  });

  it('proves restart-free only when the server says so, and names a restart', () => {
    const [putBack, applied, testRun, hpa] = entries;
    expect(proofChip(putBack)?.label).toBe('Restart-free');
    expect(proofChip(applied)?.label).toBe('Restart-free');
    expect(proofChip(testRun)).toBeNull();
    expect(proofChip(hpa)).toBeNull();
    const restarted = adaptActivityEntry({
      ...APPLIED,
      restartFree: false,
      proof: { ...APPLIED.proof!, restartFree: false, pods: [{ ...APPLIED.proof!.pods![0], restartsAfter: 1 }] },
    }, 0);
    expect(proofChip(restarted)).toEqual({ tone: 'bad', icon: 'mdi:alert-octagon-outline', label: 'Restarted' });
    expect(proofChip(adaptActivityEntry({ ...APPLIED, restartFree: false, proof: null }, 0))?.label).toBe('Not proved');
  });

  it('reads a refusal without its code and a deferral without the revert note', () => {
    expect(resultDetail(adaptActivityEntry({ ...APPLIED, result: 'REFUSED', outcome: 'REFUSED', detail: 'K8S_GITOPS_MANAGED: Not for this one.' }, 0))?.short).toBe('Not for this one.');
    expect(resultDetail(entries[0])?.short).not.toContain('Revert:');
    expect(resultDetail(entries[2])?.short).toBe('Nothing was sent to the cluster.');
    expect(resultDetail(entries[4])).toBeNull();
  });
});
